import { INestApplication } from '@nestjs/common'
import nock from 'nock'
import request from 'supertest'
import { DataSource } from 'typeorm'

import { SentEmailEntity } from '@/account/infrastructure/notification/sent-email.entity'
import {
  FAKE_OLLAMA_ORIGIN,
  OllamaChatRequestBody,
  isRefundReasonClassifierRequest,
  ollamaChatReply,
  userPrompt
} from './support/ollama-stub'
import { StartedTestApp, fetchSesMessages, startTestApp } from './support/test-app'

// Verifies, against the REAL AppModule (see test/support/test-app.ts), Payment BC's 3-way
// coordination (Card+Account synchronous Adapters), RefundEligibilityService (a Domain Service
// — pure judgment logic coordinating the Payment+Refund Aggregates), and the bidirectional
// Payment↔Account Integration Events (payment completed → debit, payment cancelled/refund
// approved → compensating credit). The RefundReasonClassifier's Ollama calls are intercepted
// by nock, so the real LLM classification path runs — only the network hop is stubbed.
describe('PaymentController (e2e) — Payment/Refund + Card/Account cross-domain', () => {
  let testApp: StartedTestApp
  let app: INestApplication
  let dataSource: DataSource

  const OWNER_ID = 'owner-1'
  const OTHER_OWNER_ID = 'owner-2'
  const PASSWORD = 'password123!'
  const tokens: Record<string, string> = {}

  // RefundReasonClassifier runs asynchronously (RefundRequested -> Outbox -> SQS -> Consumer),
  // so its Ollama calls can land while any later test is running — a persistent interceptor
  // keeps unrelated tests from failing on those background calls. Routes by reason text so the
  // classification tests below can assert a REAL (non-fallback) category.
  function stubRefundReasonClassifier(): void {
    nock(FAKE_OLLAMA_ORIGIN)
      .persist()
      .post('/api/chat', (body) => isRefundReasonClassifierRequest(body as OllamaChatRequestBody))
      .reply(200, (_uri, requestBody) => {
        const reason = userPrompt(requestBody as unknown as OllamaChatRequestBody).toLowerCase()
        const category = reason.includes('broken') || reason.includes('defective')
          ? 'DEFECTIVE_PRODUCT'
          : reason.includes('changed my mind')
            ? 'CHANGED_MIND'
            : 'OTHER'
        return ollamaChatReply(JSON.stringify({ category }))
      })
  }

  async function signUp(userId: string): Promise<void> {
    await request(app.getHttpServer()).post('/auth/sign-up').send({ userId, password: PASSWORD })
  }

  async function signIn(userId: string): Promise<string> {
    const response = await request(app.getHttpServer()).post('/auth/sign-in').send({ userId, password: PASSWORD })
    return (response.body as { accessToken: string }).accessToken
  }

  function authHeader(userId: string): string {
    return `Bearer ${tokens[userId]}`
  }

  async function createAccount(ownerId = OWNER_ID): Promise<{ accountId: string }> {
    const response = await request(app.getHttpServer())
      .post('/accounts')
      .set('Authorization', authHeader(ownerId))
      .send({ currency: 'KRW', email: 'owner1@example.com' })
    return response.body as { accountId: string }
  }

  async function deposit(accountId: string, amount: number, ownerId = OWNER_ID): Promise<void> {
    await request(app.getHttpServer())
      .post(`/accounts/${accountId}/deposit`)
      .set('Authorization', authHeader(ownerId))
      .send({ amount })
  }

  async function getBalance(accountId: string, ownerId = OWNER_ID): Promise<number> {
    const response = await request(app.getHttpServer())
      .get(`/accounts/${accountId}`)
      .set('Authorization', authHeader(ownerId))
    return response.body.balance.amount
  }

  async function issueCard(accountId: string, ownerId = OWNER_ID): Promise<{ cardId: string }> {
    const response = await request(app.getHttpServer())
      .post('/cards')
      .set('Authorization', authHeader(ownerId))
      .send({ accountId, brand: 'VISA' })
    return response.body as { cardId: string }
  }

  async function createPayment(cardId: string, amount: number, ownerId = OWNER_ID): Promise<request.Response> {
    return request(app.getHttpServer())
      .post('/payments')
      .set('Authorization', authHeader(ownerId))
      .send({ cardId, amount })
  }

  async function getCardStatus(cardId: string, ownerId = OWNER_ID): Promise<string> {
    const response = await request(app.getHttpServer())
      .get(`/cards/${cardId}`)
      .set('Authorization', authHeader(ownerId))
    return response.body.status
  }

  // A Card only becomes SUSPENDED by asynchronously reacting to an account suspension
  // (account.suspended.v1) — there's no dedicated endpoint to suspend a card directly — the
  // same polling pattern as card.e2e-spec.ts. Since Outbox draining goes through the
  // asynchronous path OutboxPoller (1-second interval)→SQS→OutboxConsumer (long polling),
  // there can be a delay before completion, so a polling budget of 150 * 200ms (30 seconds max) is used.
  async function waitForCardStatus(cardId: string, expected: string, ownerId = OWNER_ID): Promise<string> {
    for (let i = 0; i < 150; i++) {
      const status = await getCardStatus(cardId, ownerId)
      if (status === expected) return status
      await new Promise((resolve) => setTimeout(resolve, 200))
    }
    return getCardStatus(cardId, ownerId)
  }

  // The polling budget is raised for the same reason as waitForCardStatus.
  async function waitForBalance(accountId: string, expected: number, ownerId = OWNER_ID): Promise<number> {
    for (let i = 0; i < 150; i++) {
      const balance = await getBalance(accountId, ownerId)
      if (balance === expected) return balance
      await new Promise((resolve) => setTimeout(resolve, 200))
    }
    return getBalance(accountId, ownerId)
  }

  beforeAll(async () => {
    // Importing nock patches Node's process-global http module immediately, and testcontainers
    // drives the Docker daemon over that same module — keep nock inactive until the containers
    // and the app are up, then activate it for the LLM stubs.
    if (nock.isActive()) nock.restore()
    testApp = await startTestApp()
    nock.activate()
    app = testApp.app
    dataSource = testApp.dataSource
    stubRefundReasonClassifier()

    await signUp(OWNER_ID)
    await signUp(OTHER_OWNER_ID)
    tokens[OWNER_ID] = await signIn(OWNER_ID)
    tokens[OTHER_OWNER_ID] = await signIn(OTHER_OWNER_ID)
  }, 180000)

  afterAll(async () => {
    // Unpatch nock BEFORE shutdown: app.close()/container stops talk to the Docker socket over
    // the process-global http module nock patches, and the patch also must not outlive this
    // file. Any LLM call a background consumer fires during the shutdown window just DNS-fails
    // against the fake origin and takes the service's graceful fallback.
    nock.cleanAll()
    nock.restore()
    await testApp?.stop()
  })

  describe('POST /payments — checking Card/Account status via the synchronous Adapter (ACL)', () => {
    it('when_paying_with_an_inactive_card_then_returns_400_and_PAYMENT_REQUIRES_ACTIVE_CARD', async () => {
      const account = await createAccount()
      await deposit(account.accountId, 50000)
      const card = await issueCard(account.accountId)
      // Since there's no card-specific endpoint to suspend a card directly (a card only becomes
      // SUSPENDED by reacting to an account suspension), suspend the account so the linked card
      // asynchronously transitions to SUSPENDED.
      await request(app.getHttpServer())
        .post(`/accounts/${account.accountId}/suspend`)
        .set('Authorization', authHeader(OWNER_ID))
      expect(await waitForCardStatus(card.cardId, 'SUSPENDED')).toBe('SUSPENDED')

      const response = await createPayment(card.cardId, 10000)

      expect(response.status).toBe(400)
      expect(response.body.code).toBe('PAYMENT_REQUIRES_ACTIVE_CARD')
    })

    it('when_the_balance_is_insufficient_then_returns_400_and_INSUFFICIENT_BALANCE', async () => {
      const account = await createAccount()
      await deposit(account.accountId, 1000)
      const card = await issueCard(account.accountId)

      const response = await createPayment(card.cardId, 5000)

      expect(response.status).toBe(400)
      expect(response.body.code).toBe('INSUFFICIENT_BALANCE')
    })

    it('when_the_card_does_not_exist_then_returns_404_and_LINKED_CARD_NOT_FOUND', async () => {
      const response = await createPayment('non-existent-card', 1000)

      expect(response.status).toBe(404)
      expect(response.body.code).toBe('LINKED_CARD_NOT_FOUND')
    })

    it('when_the_card_belongs_to_a_different_owner_then_returns_404', async () => {
      const account = await createAccount(OWNER_ID)
      await deposit(account.accountId, 50000)
      const card = await issueCard(account.accountId, OWNER_ID)

      const response = await createPayment(card.cardId, 1000, OTHER_OWNER_ID)

      expect(response.status).toBe(404)
      expect(response.body.code).toBe('LINKED_CARD_NOT_FOUND')
    })

    it('when_the_card_is_active_and_the_balance_is_sufficient_then_returns_201_and_a_COMPLETED_payment_and_asynchronously_debits_the_account_balance', async () => {
      const account = await createAccount()
      await deposit(account.accountId, 50000)
      const card = await issueCard(account.accountId)

      const response = await createPayment(card.cardId, 10000)

      expect(response.status).toBe(201)
      expect(response.body).toMatchObject({
        cardId: card.cardId,
        accountId: account.accountId,
        ownerId: OWNER_ID,
        amount: 10000,
        status: 'COMPLETED'
      })

      // The synchronous Adapter only judges eligibility — the actual debit is performed
      // asynchronously once Account BC subscribes to payment.completed.v1 — wait for that result via polling.
      expect(await waitForBalance(account.accountId, 40000)).toBe(40000)
    })
  })

  describe('POST /payments/:paymentId/cancel — payment cancellation -> compensating credit', () => {
    it('when_cancelling_a_completed_payment_then_returns_204_and_asynchronously_restores_the_account_balance', async () => {
      const account = await createAccount()
      await deposit(account.accountId, 50000)
      const card = await issueCard(account.accountId)
      const payment = (await createPayment(card.cardId, 10000)).body as { paymentId: string }
      await waitForBalance(account.accountId, 40000)

      const cancelResponse = await request(app.getHttpServer())
        .post(`/payments/${payment.paymentId}/cancel`)
        .set('Authorization', authHeader(OWNER_ID))
        .send({ reason: 'Customer request' })

      expect(cancelResponse.status).toBe(204)
      expect(await waitForBalance(account.accountId, 50000)).toBe(50000)

      const getResponse = await request(app.getHttpServer())
        .get(`/payments/${payment.paymentId}`)
        .set('Authorization', authHeader(OWNER_ID))
      expect(getResponse.body.status).toBe('CANCELLED')
    })

    it('when_the_payment_does_not_exist_then_returns_404', async () => {
      const response = await request(app.getHttpServer())
        .post('/payments/non-existent/cancel')
        .set('Authorization', authHeader(OWNER_ID))
        .send({ reason: 'reason' })

      expect(response.status).toBe(404)
      expect(response.body.code).toBe('PAYMENT_NOT_FOUND')
    })

    it('when_cancelling_an_already_cancelled_payment_again_then_returns_400_and_PAYMENT_CANCEL_REQUIRES_COMPLETED_PAYMENT', async () => {
      const account = await createAccount()
      await deposit(account.accountId, 50000)
      const card = await issueCard(account.accountId)
      const payment = (await createPayment(card.cardId, 10000)).body as { paymentId: string }
      await request(app.getHttpServer())
        .post(`/payments/${payment.paymentId}/cancel`)
        .set('Authorization', authHeader(OWNER_ID))
        .send({ reason: 'Customer request' })

      const response = await request(app.getHttpServer())
        .post(`/payments/${payment.paymentId}/cancel`)
        .set('Authorization', authHeader(OWNER_ID))
        .send({ reason: 'Customer request' })

      expect(response.status).toBe(400)
      expect(response.body.code).toBe('PAYMENT_CANCEL_REQUIRES_COMPLETED_PAYMENT')
    })
  })

  describe('POST /payments/:paymentId/refunds — RefundEligibilityService (Domain Service) judgment', () => {
    it('when_the_refund_amount_exceeds_the_payment_amount_then_returns_201_and_a_REJECTED_status_and_the_account_is_not_credited', async () => {
      const account = await createAccount()
      await deposit(account.accountId, 50000)
      const card = await issueCard(account.accountId)
      const payment = (await createPayment(card.cardId, 10000)).body as { paymentId: string }
      await waitForBalance(account.accountId, 40000)

      const response = await request(app.getHttpServer())
        .post(`/payments/${payment.paymentId}/refunds`)
        .set('Authorization', authHeader(OWNER_ID))
        .send({ amount: 20000, reason: 'Defective product' })

      expect(response.status).toBe(201)
      expect(response.body.status).toBe('REJECTED')
      expect(response.body.decisionNote).toBe('The refund amount cannot exceed the payment amount.')

      // A rejected refund has no Domain Event, so there's nothing to drain — the balance stays unchanged.
      await new Promise((resolve) => setTimeout(resolve, 300))
      expect(await getBalance(account.accountId)).toBe(40000)
    })

    it('when_requesting_a_refund_on_a_non-completed_(cancelled)_payment_then_returns_201_and_a_REJECTED_status', async () => {
      const account = await createAccount()
      await deposit(account.accountId, 50000)
      const card = await issueCard(account.accountId)
      const payment = (await createPayment(card.cardId, 10000)).body as { paymentId: string }
      await waitForBalance(account.accountId, 40000)
      await request(app.getHttpServer())
        .post(`/payments/${payment.paymentId}/cancel`)
        .set('Authorization', authHeader(OWNER_ID))
        .send({ reason: 'Customer request' })
      await waitForBalance(account.accountId, 50000)

      const response = await request(app.getHttpServer())
        .post(`/payments/${payment.paymentId}/refunds`)
        .set('Authorization', authHeader(OWNER_ID))
        .send({ amount: 5000, reason: 'Defective product' })

      expect(response.status).toBe(201)
      expect(response.body.status).toBe('REJECTED')
      expect(response.body.decisionNote).toBe('A refund can only be requested for a completed payment.')
    })

    it('when_the_refund_request_is_valid_then_returns_201_and_an_APPROVED_status_and_asynchronously_credits_the_account', async () => {
      const account = await createAccount()
      await deposit(account.accountId, 50000)
      const card = await issueCard(account.accountId)
      const payment = (await createPayment(card.cardId, 10000)).body as { paymentId: string }
      await waitForBalance(account.accountId, 40000)

      const response = await request(app.getHttpServer())
        .post(`/payments/${payment.paymentId}/refunds`)
        .set('Authorization', authHeader(OWNER_ID))
        .send({ amount: 4000, reason: 'Partial refund' })

      expect(response.status).toBe(201)
      expect(response.body.status).toBe('APPROVED')
      expect(await waitForBalance(account.accountId, 44000)).toBe(44000)

      const listResponse = await request(app.getHttpServer())
        .get(`/payments/${payment.paymentId}/refunds`)
        .set('Authorization', authHeader(OWNER_ID))
      expect(listResponse.body.count).toBe(1)
      expect(listResponse.body.refunds[0].status).toBe('APPROVED')
    })

    it('when_the_payment_does_not_exist_then_returns_404', async () => {
      const response = await request(app.getHttpServer())
        .post('/payments/non-existent/refunds')
        .set('Authorization', authHeader(OWNER_ID))
        .send({ amount: 1000, reason: 'reason' })

      expect(response.status).toBe(404)
      expect(response.body.code).toBe('PAYMENT_NOT_FOUND')
    })
  })

  // The Ollama call behind RefundReasonClassifier is intercepted by nock (the persistent stub
  // above replies DEFECTIVE_PRODUCT for a "broken" reason), so the REAL LLM path — request
  // building, schema-constrained response parsing, category validation — runs end to end,
  // along with the real async pipeline (Domain Event → Outbox → SQS → Consumer →
  // ClassifyRefundReasonHandler → repository write). It also proves classification runs for a
  // REJECTED refund too, independent of the eligibility outcome (RefundRequested is published
  // before RefundEligibilityService's judgment even runs).
  describe('Refund reason classification + GET /refunds/reason-insights (ops analytics only)', () => {
    it('a_rejected_refund_still_gets_its_reason_classified_asynchronously', async () => {
      const account = await createAccount()
      await deposit(account.accountId, 50000)
      const card = await issueCard(account.accountId)
      const payment = (await createPayment(card.cardId, 10000)).body as { paymentId: string }
      await waitForBalance(account.accountId, 40000)

      const response = await request(app.getHttpServer())
        .post(`/payments/${payment.paymentId}/refunds`)
        .set('Authorization', authHeader(OWNER_ID))
        .send({ amount: 20000, reason: 'The item arrived broken' })
      expect(response.body.status).toBe('REJECTED')

      let reasonCategory: string | undefined
      for (let i = 0; i < 150; i++) {
        const listResponse = await request(app.getHttpServer())
          .get(`/payments/${payment.paymentId}/refunds`)
          .set('Authorization', authHeader(OWNER_ID))
        reasonCategory = listResponse.body.refunds[0]?.reasonCategory
        if (reasonCategory) break
        await new Promise((resolve) => setTimeout(resolve, 200))
      }

      // The category the stubbed LLM returned for a "broken" reason — a real classification
      // round trip, not the OTHER fallback.
      expect(reasonCategory).toBe('DEFECTIVE_PRODUCT')
    }, 60000)

    it('GET_refunds_reason-insights_reflects_a_classified_refund_in_its_category_counts', async () => {
      const before = await request(app.getHttpServer())
        .get('/refunds/reason-insights')
        .set('Authorization', authHeader(OWNER_ID))
      const totalBefore = before.body.totalClassified as number

      const account = await createAccount()
      await deposit(account.accountId, 50000)
      const card = await issueCard(account.accountId)
      const payment = (await createPayment(card.cardId, 10000)).body as { paymentId: string }
      await waitForBalance(account.accountId, 40000)
      await request(app.getHttpServer())
        .post(`/payments/${payment.paymentId}/refunds`)
        .set('Authorization', authHeader(OWNER_ID))
        .send({ amount: 4000, reason: 'Changed my mind' })

      let totalAfter = totalBefore
      for (let i = 0; i < 150; i++) {
        const after = await request(app.getHttpServer())
          .get('/refunds/reason-insights')
          .set('Authorization', authHeader(OWNER_ID))
        totalAfter = after.body.totalClassified as number
        if (totalAfter > totalBefore) break
        await new Promise((resolve) => setTimeout(resolve, 200))
      }

      expect(totalAfter).toBeGreaterThan(totalBefore)
    }, 60000)
  })

  describe('GET /payments, GET /payments/:paymentId', () => {
    it('returns_my_payment_history_with_pagination', async () => {
      const account = await createAccount()
      await deposit(account.accountId, 50000)
      const card = await issueCard(account.accountId)
      await createPayment(card.cardId, 1000)
      await createPayment(card.cardId, 2000)

      const response = await request(app.getHttpServer())
        .get('/payments')
        .set('Authorization', authHeader(OWNER_ID))
        .query({ page: 0, take: 20 })

      expect(response.status).toBe(200)
      expect(response.body.count).toBeGreaterThanOrEqual(2)
    })

    it('when_looking_up_a_payment_belonging_to_a_different_owner_then_returns_404', async () => {
      const account = await createAccount(OWNER_ID)
      await deposit(account.accountId, 50000)
      const card = await issueCard(account.accountId, OWNER_ID)
      const payment = (await createPayment(card.cardId, 1000, OWNER_ID)).body as { paymentId: string }

      const response = await request(app.getHttpServer())
        .get(`/payments/${payment.paymentId}`)
        .set('Authorization', authHeader(OTHER_OWNER_ID))

      expect(response.status).toBe(404)
    })
  })

  // Money movements in Account BC trigger real SES emails (MoneyDeposited etc.) — with no
  // NotificationService stub, those sends must actually land in LocalStack.
  describe('SES notification — the real NotificationService runs alongside the payment flows', () => {
    it('depositing_into_the_linked_account_sends_a_real_email_recorded_in_the_DB_and_in_LocalStack', async () => {
      const account = await createAccount()
      await deposit(account.accountId, 50000)

      const sentEmailRepo = dataSource.getRepository(SentEmailEntity)
      let sentEmail: SentEmailEntity | null = null
      for (let i = 0; i < 150; i++) {
        sentEmail = await sentEmailRepo.findOneBy({ accountId: account.accountId, eventType: 'MoneyDeposited' })
        if (sentEmail) break
        await new Promise((resolve) => setTimeout(resolve, 200))
      }

      expect(sentEmail).not.toBeNull()
      expect(sentEmail?.recipient).toBe('owner1@example.com')
      expect(sentEmail?.sesMessageId.length).toBeGreaterThan(0)

      const sesMessages = await fetchSesMessages(testApp.awsEndpoint)
      const matched = sesMessages.find((message) => message.Id === sentEmail?.sesMessageId)
      expect(matched).toBeDefined()
    }, 60000)
  })
})

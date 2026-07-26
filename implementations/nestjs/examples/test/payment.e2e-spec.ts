import { BadRequestException, INestApplication, ValidationPipe } from '@nestjs/common'
import { ConfigModule } from '@nestjs/config'
import { ScheduleModule } from '@nestjs/schedule'
import { Test } from '@nestjs/testing'
import { TypeOrmModule } from '@nestjs/typeorm'
import { LocalstackContainer, StartedLocalStackContainer } from '@testcontainers/localstack'
import { PostgreSqlContainer, StartedPostgreSqlContainer } from '@testcontainers/postgresql'
import request from 'supertest'

import { AccountModule } from '@/account/account-module'
import { NotificationService } from '@/account/application/service/notification-service'
import { AccountEntity } from '@/account/infrastructure/entity/account.entity'
import { TransactionEntity } from '@/account/infrastructure/entity/transaction.entity'
import { SentEmailEntity } from '@/account/infrastructure/notification/sent-email.entity'
import { AuthModule } from '@/auth/auth-module'
import { CredentialEntity } from '@/auth/infrastructure/entity/credential.entity'
import { CardModule } from '@/card/card-module'
import { CardEntity } from '@/card/infrastructure/entity/card.entity'
import { jwtConfig } from '@/config/jwt.config'
import { OutboxEntity } from '@/outbox/outbox.entity'
import { OutboxModule } from '@/outbox/outbox-module'
import { PaymentModule } from '@/payment/payment-module'
import { PaymentEntity } from '@/payment/infrastructure/entity/payment.entity'
import { RefundEntity } from '@/payment/infrastructure/entity/refund.entity'
import { SentCardStatementEntity } from '@/payment/infrastructure/notification/sent-card-statement.entity'
import { TaskOutboxEntity } from '@/task-queue/task-outbox.entity'
import { TaskQueueModule } from '@/task-queue/task-queue-module'
import { createDomainEventQueue } from './support/sqs-test-queue'
import { createTaskQueue } from './support/task-queue-test-queue'

// Verifies, via real app bootstrap + Postgres, Payment BC's 3-way coordination
// (Card+Account synchronous Adapters), RefundEligibilityService (a Domain Service — pure
// judgment logic coordinating the Payment+Refund Aggregates), and the bidirectional
// Payment↔Account Integration Events (payment completed → debit, payment cancelled/refund
// approved → compensating credit). Follows the same pattern as card.e2e-spec.ts.
describe('PaymentController (e2e) — Payment/Refund + Card/Account cross-domain', () => {
  let container: StartedPostgreSqlContainer
  let localstack: StartedLocalStackContainer
  let app: INestApplication

  const OWNER_ID = 'owner-1'
  const OTHER_OWNER_ID = 'owner-2'
  const PASSWORD = 'password123!'
  const tokens: Record<string, string> = {}

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

  // The polling budget is raised for the same reason as card.e2e-spec.ts's waitForCardStatus.
  async function waitForBalance(accountId: string, expected: number, ownerId = OWNER_ID): Promise<number> {
    for (let i = 0; i < 150; i++) {
      const balance = await getBalance(accountId, ownerId)
      if (balance === expected) return balance
      await new Promise((resolve) => setTimeout(resolve, 200))
    }
    return getBalance(accountId, ownerId)
  }

  beforeAll(async () => {
    container = await new PostgreSqlContainer('postgres:16-alpine').start()
    localstack = await new LocalstackContainer('localstack/localstack:3.0')
      .withEnvironment({ SERVICES: 'sqs' })
      .start()

    const sqsEndpoint = localstack.getConnectionUri()
    process.env.AWS_ENDPOINT_URL = sqsEndpoint
    process.env.AWS_REGION = 'us-east-1'
    process.env.AWS_ACCESS_KEY_ID = 'test'
    process.env.AWS_SECRET_ACCESS_KEY = 'test'
    process.env.SQS_DOMAIN_EVENT_QUEUE_URL = await createDomainEventQueue(sqsEndpoint)
    process.env.SQS_TASK_QUEUE_URL = await createTaskQueue(sqsEndpoint)

    const moduleRef = await Test.createTestingModule({
      imports: [
        ConfigModule.forRoot({ isGlobal: true, load: [jwtConfig] }),
        ScheduleModule.forRoot(),
        TypeOrmModule.forRoot({
          type: 'postgres',
          url: container.getConnectionUri(),
          entities: [
            AccountEntity, TransactionEntity, CardEntity, PaymentEntity, RefundEntity, SentCardStatementEntity,
            OutboxEntity, SentEmailEntity, CredentialEntity, TaskOutboxEntity
          ],
          synchronize: true
        }),
        OutboxModule,
        TaskQueueModule,
        AuthModule,
        AccountModule,
        CardModule,
        PaymentModule
      ]
    })
      // SES (a technical service) isn't this test's concern, so it's replaced with a no-op.
      .overrideProvider(NotificationService)
      .useValue({ sendEmail: async () => undefined })
      .compile()

    app = moduleRef.createNestApplication()
    app.useGlobalPipes(new ValidationPipe({
      whitelist: true,
      transform: true,
      exceptionFactory: (errors) => {
        const message = errors.flatMap((error) => Object.values(error.constraints ?? {}))
        return new BadRequestException({ statusCode: 400, code: 'VALIDATION_FAILED', message, error: 'Bad Request' })
      }
    }))
    await app.init()

    await signUp(OWNER_ID)
    await signUp(OTHER_OWNER_ID)
    tokens[OWNER_ID] = await signIn(OWNER_ID)
    tokens[OTHER_OWNER_ID] = await signIn(OTHER_OWNER_ID)
  }, 120000)

  afterAll(async () => {
    delete process.env.AWS_ENDPOINT_URL
    delete process.env.AWS_REGION
    delete process.env.AWS_ACCESS_KEY_ID
    delete process.env.AWS_SECRET_ACCESS_KEY
    delete process.env.SQS_DOMAIN_EVENT_QUEUE_URL
    delete process.env.SQS_TASK_QUEUE_URL
    await app?.close()
    await container?.stop()
    await localstack?.stop()
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
})

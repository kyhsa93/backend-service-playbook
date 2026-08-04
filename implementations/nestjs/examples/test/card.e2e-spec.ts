import { INestApplication } from '@nestjs/common'
import request from 'supertest'
import { DataSource } from 'typeorm'

import { SentEmailEntity } from '@/account/infrastructure/notification/sent-email.entity'
import { StartedTestApp, fetchSesMessages, startTestApp } from './support/test-app'

// Boots the REAL AppModule (see test/support/test-app.ts) — including the real
// NotificationService: SES emails actually go through LocalStack (no provider stub), so the
// cross-domain flows here run exactly the modules production runs.
describe('CardController (e2e) — cross-domain Account<->Card', () => {
  let testApp: StartedTestApp
  let app: INestApplication
  let dataSource: DataSource

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

  async function issueCard(accountId: string, ownerId = OWNER_ID): Promise<request.Response> {
    return request(app.getHttpServer())
      .post('/cards')
      .set('Authorization', authHeader(ownerId))
      .send({ accountId, brand: 'VISA' })
  }

  async function getCardStatus(cardId: string, ownerId = OWNER_ID): Promise<string> {
    const response = await request(app.getHttpServer())
      .get(`/cards/${cardId}`)
      .set('Authorization', authHeader(ownerId))
    return response.body.status
  }

  // Outbox draining goes through the real asynchronous path OutboxPoller (1-second interval)
  // → SQS → OutboxConsumer (long polling), so completion can lag — a polling budget of
  // 150 * 200ms (30 seconds max) comfortably absorbs the poller tick + SQS round trip +
  // consumer processing time.
  async function waitForCardStatus(cardId: string, expected: string, ownerId = OWNER_ID): Promise<string> {
    for (let i = 0; i < 150; i++) {
      const status = await getCardStatus(cardId, ownerId)
      if (status === expected) return status
      await new Promise((resolve) => setTimeout(resolve, 200))
    }
    return getCardStatus(cardId, ownerId)
  }

  beforeAll(async () => {
    testApp = await startTestApp()
    app = testApp.app
    dataSource = testApp.dataSource

    await signUp(OWNER_ID)
    await signUp(OTHER_OWNER_ID)
    tokens[OWNER_ID] = await signIn(OWNER_ID)
    tokens[OTHER_OWNER_ID] = await signIn(OTHER_OWNER_ID)
  }, 180000)

  afterAll(async () => {
    await testApp?.stop()
  })

  describe('POST /cards — checking Account status via the synchronous Adapter (ACL)', () => {
    it('issuing_a_card_for_an_active_account_returns_201_and_an_ACTIVE_card', async () => {
      const account = await createAccount()

      const response = await issueCard(account.accountId)

      expect(response.status).toBe(201)
      expect(response.body).toMatchObject({
        accountId: account.accountId,
        ownerId: OWNER_ID,
        brand: 'VISA',
        status: 'ACTIVE'
      })
      expect(response.body.cardId).toEqual(expect.any(String))
    })

    it('when_the_account_does_not_exist_then_returns_404_and_LINKED_ACCOUNT_NOT_FOUND', async () => {
      const response = await issueCard('non-existent-account')

      expect(response.status).toBe(404)
      expect(response.body.code).toBe('LINKED_ACCOUNT_NOT_FOUND')
    })

    it('when_the_account_belongs_to_a_different_owner_then_returns_404', async () => {
      const account = await createAccount(OWNER_ID)

      const response = await issueCard(account.accountId, OTHER_OWNER_ID)

      expect(response.status).toBe(404)
      expect(response.body.code).toBe('LINKED_ACCOUNT_NOT_FOUND')
    })

    it('when_the_account_is_suspended_then_returns_400_and_CARD_ISSUE_REQUIRES_ACTIVE_ACCOUNT', async () => {
      const account = await createAccount()
      await request(app.getHttpServer())
        .post(`/accounts/${account.accountId}/suspend`)
        .set('Authorization', authHeader(OWNER_ID))

      const response = await issueCard(account.accountId)

      expect(response.status).toBe(400)
      expect(response.body.code).toBe('CARD_ISSUE_REQUIRES_ACTIVE_ACCOUNT')
    })
  })

  describe('Async Integration Event — Card reacts to Account status changes', () => {
    it('when_the_account_is_suspended_the_linked_card_transitions_to_SUSPENDED', async () => {
      const account = await createAccount()
      const issued = await issueCard(account.accountId)
      const cardId = issued.body.cardId as string
      expect(await getCardStatus(cardId)).toBe('ACTIVE')

      const suspendResponse = await request(app.getHttpServer())
        .post(`/accounts/${account.accountId}/suspend`)
        .set('Authorization', authHeader(OWNER_ID))
      expect(suspendResponse.status).toBe(204)

      expect(await waitForCardStatus(cardId, 'SUSPENDED')).toBe('SUSPENDED')
    })

    it('when_the_account_is_closed_the_linked_card_transitions_to_CANCELLED', async () => {
      const account = await createAccount()
      const issued = await issueCard(account.accountId)
      const cardId = issued.body.cardId as string

      const closeResponse = await request(app.getHttpServer())
        .post(`/accounts/${account.accountId}/close`)
        .set('Authorization', authHeader(OWNER_ID))
      expect(closeResponse.status).toBe(204)

      expect(await waitForCardStatus(cardId, 'CANCELLED')).toBe('CANCELLED')
    })
  })

  // The Account BC's event handlers send real SES emails alongside the Integration Events the
  // tests above consume — with no NotificationService stub, those sends must actually land.
  describe('SES notification — the real NotificationService runs alongside the card flows', () => {
    it('creating_the_linked_account_sends_a_real_email_recorded_in_the_DB_and_in_LocalStack', async () => {
      const account = await createAccount()

      const sentEmailRepo = dataSource.getRepository(SentEmailEntity)
      let sentEmail: SentEmailEntity | null = null
      for (let i = 0; i < 150; i++) {
        sentEmail = await sentEmailRepo.findOneBy({ accountId: account.accountId, eventType: 'AccountCreated' })
        if (sentEmail) break
        await new Promise((resolve) => setTimeout(resolve, 200))
      }

      expect(sentEmail).not.toBeNull()
      expect(sentEmail?.recipient).toBe('owner1@example.com')
      expect(sentEmail?.sesMessageId.length).toBeGreaterThan(0)

      const sesMessages = await fetchSesMessages(testApp.awsEndpoint)
      const matched = sesMessages.find((message) => message.Id === sentEmail?.sesMessageId)
      expect(matched).toBeDefined()
      expect(matched?.Destination.ToAddresses).toContain('owner1@example.com')
    }, 60000)
  })
})

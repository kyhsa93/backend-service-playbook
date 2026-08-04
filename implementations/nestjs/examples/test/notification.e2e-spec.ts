import { INestApplication } from '@nestjs/common'
import request from 'supertest'
import { DataSource } from 'typeorm'

import { SentEmailEntity } from '@/account/infrastructure/notification/sent-email.entity'
import { StartedTestApp, fetchSesMessages, startTestApp } from './support/test-app'

// Boots the REAL AppModule (see test/support/test-app.ts). The AccountCreated/MoneyDeposited
// Domain Event Handlers (which call NotificationService) only run once OutboxPoller publishes
// the event to SQS and OutboxConsumer receives it — LocalStack provides both SQS and SES, and
// the sends are verified against LocalStack's real SES send log.
describe('SES email sent on Account domain events (e2e)', () => {
  let testApp: StartedTestApp
  let app: INestApplication
  let dataSource: DataSource

  const OWNER_ID = 'owner-1'
  const RECIPIENT_EMAIL = 'owner1@example.com'
  const PASSWORD = 'password123!'
  let ownerToken: string

  beforeAll(async () => {
    testApp = await startTestApp()
    app = testApp.app
    dataSource = testApp.dataSource

    await request(app.getHttpServer()).post('/auth/sign-up').send({ userId: OWNER_ID, password: PASSWORD })
    const signInResponse = await request(app.getHttpServer())
      .post('/auth/sign-in')
      .send({ userId: OWNER_ID, password: PASSWORD })
    ownerToken = (signInResponse.body as { accessToken: string }).accessToken
  }, 180000)

  afterAll(async () => {
    await testApp?.stop()
  })

  // The handlers go through the asynchronous path OutboxPoller (1-second interval) → SQS →
  // OutboxConsumer (long polling), so an immediate-lookup assertion is replaced with polling
  // (the same pattern as card.e2e-spec.ts's waitForCardStatus).
  async function waitForSentEmail(accountId: string, eventType: string): Promise<SentEmailEntity | null> {
    for (let i = 0; i < 150; i++) {
      const sentEmail = await dataSource.getRepository(SentEmailEntity).findOneBy({ accountId, eventType })
      if (sentEmail) return sentEmail
      await new Promise((resolve) => setTimeout(resolve, 200))
    }
    return dataSource.getRepository(SentEmailEntity).findOneBy({ accountId, eventType })
  }

  it('on_account_creation_an_email_is_sent_via_SES_and_the_send_record_is_stored_in_the_DB_and_localstack', async () => {
    const response = await request(app.getHttpServer())
      .post('/accounts')
      .set('Authorization', `Bearer ${ownerToken}`)
      .send({ currency: 'KRW', email: RECIPIENT_EMAIL })

    expect(response.status).toBe(201)
    const accountId = response.body.accountId as string

    const sentEmail = await waitForSentEmail(accountId, 'AccountCreated')

    expect(sentEmail).not.toBeNull()
    expect(sentEmail?.recipient).toBe(RECIPIENT_EMAIL)
    expect(sentEmail?.sesMessageId.length).toBeGreaterThan(0)

    const sesMessages = await fetchSesMessages(testApp.awsEndpoint)
    const matched = sesMessages.find((message) => message.Id === sentEmail?.sesMessageId)

    expect(matched).toBeDefined()
    expect(matched?.Destination.ToAddresses).toContain(RECIPIENT_EMAIL)
  })

  it('on_deposit_an_email_is_sent_via_SES_and_the_send_record_is_stored_in_the_DB', async () => {
    const createResponse = await request(app.getHttpServer())
      .post('/accounts')
      .set('Authorization', `Bearer ${ownerToken}`)
      .send({ currency: 'KRW', email: RECIPIENT_EMAIL })
    const accountId = createResponse.body.accountId as string

    const depositResponse = await request(app.getHttpServer())
      .post(`/accounts/${accountId}/deposit`)
      .set('Authorization', `Bearer ${ownerToken}`)
      .send({ amount: 10000 })

    expect(depositResponse.status).toBe(201)

    const sentEmail = await waitForSentEmail(accountId, 'MoneyDeposited')

    expect(sentEmail).not.toBeNull()
    expect(sentEmail?.recipient).toBe(RECIPIENT_EMAIL)

    const sesMessages = await fetchSesMessages(testApp.awsEndpoint)
    const matched = sesMessages.find((message) => message.Id === sentEmail?.sesMessageId)
    expect(matched).toBeDefined()
  })
})

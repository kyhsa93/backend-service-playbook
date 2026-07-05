import { INestApplication } from '@nestjs/common'
import { Test } from '@nestjs/testing'
import { TypeOrmModule } from '@nestjs/typeorm'
import { PostgreSqlContainer, StartedPostgreSqlContainer } from '@testcontainers/postgresql'
import { LocalstackContainer, StartedLocalStackContainer } from '@testcontainers/localstack'
import { SESClient, VerifyEmailIdentityCommand } from '@aws-sdk/client-ses'
import request from 'supertest'
import { DataSource } from 'typeorm'

import { AccountModule } from '@/account/account-module'
import { AccountEntity } from '@/account/infrastructure/entity/account.entity'
import { TransactionEntity } from '@/account/infrastructure/entity/transaction.entity'
import { OutboxEntity } from '@/outbox/outbox.entity'
import { OutboxModule } from '@/outbox/outbox-module'
import { SentEmailEntity } from '@/notification/sent-email.entity'

interface SesMessage {
  Id: string
  Source: string
  Destination: { ToAddresses: string[] }
  Subject: string
}

async function fetchSesMessages(endpoint: string): Promise<SesMessage[]> {
  const response = await fetch(`${endpoint}/_aws/ses`)
  const body = (await response.json()) as { messages: SesMessage[] }
  return body.messages
}

describe('Account 도메인 이벤트 발생시 SES 이메일 발송 (e2e)', () => {
  let postgres: StartedPostgreSqlContainer
  let localstack: StartedLocalStackContainer
  let sesEndpoint: string
  let app: INestApplication
  let dataSource: DataSource

  const OWNER_ID = 'owner-1'
  const RECIPIENT_EMAIL = 'owner1@example.com'

  beforeAll(async () => {
    postgres = await new PostgreSqlContainer('postgres:16-alpine').start()
    localstack = await new LocalstackContainer('localstack/localstack:3.0')
      .withEnvironment({ SERVICES: 'ses' })
      .start()

    sesEndpoint = localstack.getConnectionUri()
    process.env.AWS_ENDPOINT_URL = sesEndpoint
    process.env.AWS_REGION = 'us-east-1'
    process.env.AWS_ACCESS_KEY_ID = 'test'
    process.env.AWS_SECRET_ACCESS_KEY = 'test'
    process.env.SES_SENDER_EMAIL = 'no-reply@backend-service-playbook.example.com'

    const verificationClient = new SESClient({
      region: 'us-east-1',
      endpoint: sesEndpoint,
      credentials: { accessKeyId: 'test', secretAccessKey: 'test' }
    })
    await verificationClient.send(new VerifyEmailIdentityCommand({ EmailAddress: process.env.SES_SENDER_EMAIL }))

    const moduleRef = await Test.createTestingModule({
      imports: [
        TypeOrmModule.forRoot({
          type: 'postgres',
          url: postgres.getConnectionUri(),
          entities: [AccountEntity, TransactionEntity, OutboxEntity, SentEmailEntity],
          synchronize: true
        }),
        OutboxModule,
        AccountModule
      ]
    }).compile()

    app = moduleRef.createNestApplication()
    await app.init()
    dataSource = moduleRef.get(DataSource)
  }, 180000)

  afterAll(async () => {
    delete process.env.AWS_ENDPOINT_URL
    delete process.env.AWS_REGION
    delete process.env.AWS_ACCESS_KEY_ID
    delete process.env.AWS_SECRET_ACCESS_KEY
    delete process.env.SES_SENDER_EMAIL
    await app?.close()
    await postgres?.stop()
    await localstack?.stop()
  })

  it('계좌_생성시_SES로_이메일이_발송되고_발송_내역이_DB와_localstack에_기록된다', async () => {
    const response = await request(app.getHttpServer())
      .post('/accounts')
      .set('X-User-Id', OWNER_ID)
      .send({ currency: 'KRW', email: RECIPIENT_EMAIL })

    expect(response.status).toBe(201)
    const accountId = response.body.accountId as string

    const sentEmail = await dataSource.getRepository(SentEmailEntity)
      .findOneBy({ accountId, eventType: 'AccountCreated' })

    expect(sentEmail).not.toBeNull()
    expect(sentEmail?.recipient).toBe(RECIPIENT_EMAIL)
    expect(sentEmail?.sesMessageId.length).toBeGreaterThan(0)

    const sesMessages = await fetchSesMessages(sesEndpoint)
    const matched = sesMessages.find((message) => message.Id === sentEmail?.sesMessageId)

    expect(matched).toBeDefined()
    expect(matched?.Destination.ToAddresses).toContain(RECIPIENT_EMAIL)
  })

  it('입금시_SES로_이메일이_발송되고_발송_내역이_DB에_기록된다', async () => {
    const createResponse = await request(app.getHttpServer())
      .post('/accounts')
      .set('X-User-Id', OWNER_ID)
      .send({ currency: 'KRW', email: RECIPIENT_EMAIL })
    const accountId = createResponse.body.accountId as string

    const depositResponse = await request(app.getHttpServer())
      .post(`/accounts/${accountId}/deposit`)
      .set('X-User-Id', OWNER_ID)
      .send({ amount: 10000 })

    expect(depositResponse.status).toBe(201)

    const sentEmail = await dataSource.getRepository(SentEmailEntity)
      .findOneBy({ accountId, eventType: 'MoneyDeposited' })

    expect(sentEmail).not.toBeNull()
    expect(sentEmail?.recipient).toBe(RECIPIENT_EMAIL)

    const sesMessages = await fetchSesMessages(sesEndpoint)
    const matched = sesMessages.find((message) => message.Id === sentEmail?.sesMessageId)
    expect(matched).toBeDefined()
  })
})

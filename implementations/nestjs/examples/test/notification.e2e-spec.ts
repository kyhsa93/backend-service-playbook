import { INestApplication } from '@nestjs/common'
import { ConfigModule } from '@nestjs/config'
import { ScheduleModule } from '@nestjs/schedule'
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
import { SentEmailEntity } from '@/account/infrastructure/notification/sent-email.entity'
import { AuthModule } from '@/auth/auth-module'
import { CredentialEntity } from '@/auth/infrastructure/entity/credential.entity'
import { jwtConfig } from '@/config/jwt.config'
import { OutboxEntity } from '@/outbox/outbox.entity'
import { OutboxModule } from '@/outbox/outbox-module'
import { TaskOutboxEntity } from '@/task-queue/task-outbox.entity'
import { TaskQueueModule } from '@/task-queue/task-queue-module'
import { createDomainEventQueue } from './support/sqs-test-queue'
import { createTaskQueue } from './support/task-queue-test-queue'

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
  const PASSWORD = 'password123!'
  let ownerToken: string

  beforeAll(async () => {
    postgres = await new PostgreSqlContainer('postgres:16-alpine').start()
    localstack = await new LocalstackContainer('localstack/localstack:3.0')
      .withEnvironment({ SERVICES: 'ses,sqs' })
      .start()

    sesEndpoint = localstack.getConnectionUri()
    process.env.AWS_ENDPOINT_URL = sesEndpoint
    process.env.AWS_REGION = 'us-east-1'
    process.env.AWS_ACCESS_KEY_ID = 'test'
    process.env.AWS_SECRET_ACCESS_KEY = 'test'
    process.env.SES_SENDER_EMAIL = 'no-reply@backend-service-playbook.example.com'
    // AccountCreated/MoneyDeposited Domain Event Handler(NotificationService 호출)는
    // 이제 같은 프로세스 안에서 즉시 실행되지 않는다 — OutboxPoller가 이 이벤트를 SQS로
    // 발행하고 OutboxConsumer가 수신해야 실행된다. 그래서 SES뿐 아니라 SQS도 필요하다.
    process.env.SQS_DOMAIN_EVENT_QUEUE_URL = await createDomainEventQueue(sesEndpoint)
    process.env.SQS_TASK_QUEUE_URL = await createTaskQueue(sesEndpoint)

    const verificationClient = new SESClient({
      region: 'us-east-1',
      endpoint: sesEndpoint,
      credentials: { accessKeyId: 'test', secretAccessKey: 'test' }
    })
    await verificationClient.send(new VerifyEmailIdentityCommand({ EmailAddress: process.env.SES_SENDER_EMAIL }))

    const moduleRef = await Test.createTestingModule({
      imports: [
        ConfigModule.forRoot({ isGlobal: true, load: [jwtConfig] }),
        ScheduleModule.forRoot(),
        TypeOrmModule.forRoot({
          type: 'postgres',
          url: postgres.getConnectionUri(),
          entities: [AccountEntity, TransactionEntity, OutboxEntity, SentEmailEntity, CredentialEntity, TaskOutboxEntity],
          synchronize: true
        }),
        OutboxModule,
        TaskQueueModule,
        AuthModule,
        AccountModule
      ]
    }).compile()

    app = moduleRef.createNestApplication()
    await app.init()
    dataSource = moduleRef.get(DataSource)

    await request(app.getHttpServer()).post('/auth/sign-up').send({ userId: OWNER_ID, password: PASSWORD })
    const signInResponse = await request(app.getHttpServer())
      .post('/auth/sign-in')
      .send({ userId: OWNER_ID, password: PASSWORD })
    ownerToken = (signInResponse.body as { accessToken: string }).accessToken
  }, 180000)

  afterAll(async () => {
    delete process.env.AWS_ENDPOINT_URL
    delete process.env.AWS_REGION
    delete process.env.AWS_ACCESS_KEY_ID
    delete process.env.AWS_SECRET_ACCESS_KEY
    delete process.env.SES_SENDER_EMAIL
    delete process.env.SQS_DOMAIN_EVENT_QUEUE_URL
    delete process.env.SQS_TASK_QUEUE_URL
    await app?.close()
    await postgres?.stop()
    await localstack?.stop()
  })

  // AccountCreatedHandler/MoneyDepositedHandler(SES 발송)는 더 이상 같은 프로세스 안에서
  // 커맨드 응답 전에 끝나지 않는다 — OutboxPoller(1초 주기)가 이벤트를 SQS로 발행하고
  // OutboxConsumer(long polling)가 수신해야 실행된다. 즉시 조회하던 이전 assert를
  // 폴링으로 바꾼다(card.e2e-spec.ts의 waitForCardStatus와 동일한 패턴).
  async function waitForSentEmail(accountId: string, eventType: string): Promise<SentEmailEntity | null> {
    for (let i = 0; i < 150; i++) {
      const sentEmail = await dataSource.getRepository(SentEmailEntity).findOneBy({ accountId, eventType })
      if (sentEmail) return sentEmail
      await new Promise((resolve) => setTimeout(resolve, 200))
    }
    return dataSource.getRepository(SentEmailEntity).findOneBy({ accountId, eventType })
  }

  it('계좌_생성시_SES로_이메일이_발송되고_발송_내역이_DB와_localstack에_기록된다', async () => {
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

    const sesMessages = await fetchSesMessages(sesEndpoint)
    const matched = sesMessages.find((message) => message.Id === sentEmail?.sesMessageId)

    expect(matched).toBeDefined()
    expect(matched?.Destination.ToAddresses).toContain(RECIPIENT_EMAIL)
  })

  it('입금시_SES로_이메일이_발송되고_발송_내역이_DB에_기록된다', async () => {
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

    const sesMessages = await fetchSesMessages(sesEndpoint)
    const matched = sesMessages.find((message) => message.Id === sentEmail?.sesMessageId)
    expect(matched).toBeDefined()
  })
})

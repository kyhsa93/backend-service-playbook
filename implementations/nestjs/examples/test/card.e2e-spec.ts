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
import { TaskOutboxEntity } from '@/task-queue/task-outbox.entity'
import { TaskQueueModule } from '@/task-queue/task-queue-module'
import { createDomainEventQueue } from './support/sqs-test-queue'
import { createTaskQueue } from './support/task-queue-test-queue'

describe('CardController (e2e) — cross-domain Account<->Card', () => {
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

  // Since Outbox draining now goes through the real asynchronous path
  // OutboxPoller (1-second interval) → SQS → OutboxConsumer (long polling), the previous
  // polling budget (20 * 100ms = 2 seconds), sized for finishing immediately in the same
  // process, may not be enough — it's raised to 150 * 200ms (30 seconds max) to comfortably
  // absorb the poller tick + SQS round trip + consumer processing time.
  async function waitForCardStatus(cardId: string, expected: string, ownerId = OWNER_ID): Promise<string> {
    for (let i = 0; i < 150; i++) {
      const status = await getCardStatus(cardId, ownerId)
      if (status === expected) return status
      await new Promise((resolve) => setTimeout(resolve, 200))
    }
    return getCardStatus(cardId, ownerId)
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
          entities: [AccountEntity, TransactionEntity, CardEntity, OutboxEntity, SentEmailEntity, CredentialEntity, TaskOutboxEntity],
          synchronize: true
        }),
        OutboxModule,
        TaskQueueModule,
        AuthModule,
        AccountModule,
        CardModule
      ]
    })
      // SES (a technical service) isn't this test's concern, so it's replaced with a no-op.
      // This way, the account event handler doesn't throw on an email failure, and only Integration Event delivery is verified.
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
})

import { BadRequestException, INestApplication, ValidationPipe } from '@nestjs/common'
import { ConfigModule } from '@nestjs/config'
import { ScheduleModule } from '@nestjs/schedule'
import { Test } from '@nestjs/testing'
import { TypeOrmModule } from '@nestjs/typeorm'
import { LocalstackContainer, StartedLocalStackContainer } from '@testcontainers/localstack'
import { PostgreSqlContainer, StartedPostgreSqlContainer } from '@testcontainers/postgresql'
import request from 'supertest'

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

// Since Outbox draining is now fully asynchronous via OutboxPoller (periodic polling) +
// OutboxConsumer (SQS receiving), every e2e spec that imports OutboxModule needs a real SQS
// (LocalStack) — without one, the Poller/Consumer just pile up connection-failure logs every tick.
describe('AccountController (e2e)', () => {
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

  async function createAccount(
    ownerId = OWNER_ID,
    currency = 'KRW',
    email = 'owner1@example.com'
  ): Promise<{ accountId: string }> {
    const response = await request(app.getHttpServer())
      .post('/accounts')
      .set('Authorization', authHeader(ownerId))
      .send({ currency, email })
    return response.body as { accountId: string }
  }

  describe('POST /accounts', () => {
    it('when_the_creation_request_is_valid_then_returns_201_and_the_account_info', async () => {
      const response = await request(app.getHttpServer())
        .post('/accounts')
        .set('Authorization', authHeader(OWNER_ID))
        .send({ currency: 'KRW', email: 'owner1@example.com' })

      expect(response.status).toBe(201)
      expect(response.body).toMatchObject({
        ownerId: OWNER_ID,
        email: 'owner1@example.com',
        balance: { amount: 0, currency: 'KRW' },
        status: 'ACTIVE'
      })
      expect(response.body.accountId).toEqual(expect.any(String))
      expect(response.body.createdAt).toBeDefined()
    })

    it('when_currency_is_missing_then_returns_400_and_VALIDATION_FAILED', async () => {
      const response = await request(app.getHttpServer())
        .post('/accounts')
        .set('Authorization', authHeader(OWNER_ID))
        .send({ email: 'owner1@example.com' })

      expect(response.status).toBe(400)
      expect(response.body.code).toBe('VALIDATION_FAILED')
    })

    it('when_email_is_invalid_then_returns_400_and_VALIDATION_FAILED', async () => {
      const response = await request(app.getHttpServer())
        .post('/accounts')
        .set('Authorization', authHeader(OWNER_ID))
        .send({ currency: 'KRW', email: 'not-an-email' })

      expect(response.status).toBe(400)
      expect(response.body.code).toBe('VALIDATION_FAILED')
    })
  })

  describe('POST /accounts/:accountId/deposit', () => {
    it('when_the_deposit_request_is_valid_then_returns_201_and_the_transaction_details', async () => {
      const account = await createAccount()

      const response = await request(app.getHttpServer())
        .post(`/accounts/${account.accountId}/deposit`)
        .set('Authorization', authHeader(OWNER_ID))
        .send({ amount: 10000 })

      expect(response.status).toBe(201)
      expect(response.body).toMatchObject({
        accountId: account.accountId,
        type: 'DEPOSIT',
        amount: { amount: 10000, currency: 'KRW' }
      })
      expect(response.body.transactionId).toEqual(expect.any(String))
    })

    it('when_the_account_does_not_exist_then_returns_404_and_ACCOUNT_NOT_FOUND', async () => {
      const response = await request(app.getHttpServer())
        .post('/accounts/non-existent/deposit')
        .set('Authorization', authHeader(OWNER_ID))
        .send({ amount: 10000 })

      expect(response.status).toBe(404)
      expect(response.body.code).toBe('ACCOUNT_NOT_FOUND')
    })

    it('when_the_account_belongs_to_a_different_owner_then_returns_404', async () => {
      const account = await createAccount(OWNER_ID)

      const response = await request(app.getHttpServer())
        .post(`/accounts/${account.accountId}/deposit`)
        .set('Authorization', authHeader(OTHER_OWNER_ID))
        .send({ amount: 10000 })

      expect(response.status).toBe(404)
    })

    it('when_the_amount_is_0_or_less_then_returns_400', async () => {
      const account = await createAccount()

      const response = await request(app.getHttpServer())
        .post(`/accounts/${account.accountId}/deposit`)
        .set('Authorization', authHeader(OWNER_ID))
        .send({ amount: 0 })

      expect(response.status).toBe(400)
    })

    it('when_the_account_is_suspended_then_returns_400_and_DEPOSIT_REQUIRES_ACTIVE_ACCOUNT', async () => {
      const account = await createAccount()
      await request(app.getHttpServer())
        .post(`/accounts/${account.accountId}/suspend`)
        .set('Authorization', authHeader(OWNER_ID))

      const response = await request(app.getHttpServer())
        .post(`/accounts/${account.accountId}/deposit`)
        .set('Authorization', authHeader(OWNER_ID))
        .send({ amount: 10000 })

      expect(response.status).toBe(400)
      expect(response.body.code).toBe('DEPOSIT_REQUIRES_ACTIVE_ACCOUNT')
    })
  })

  describe('POST /accounts/:accountId/withdraw', () => {
    it('when_the_withdrawal_request_is_valid_then_returns_201_and_the_transaction_details', async () => {
      const account = await createAccount()
      await request(app.getHttpServer())
        .post(`/accounts/${account.accountId}/deposit`)
        .set('Authorization', authHeader(OWNER_ID))
        .send({ amount: 10000 })

      const response = await request(app.getHttpServer())
        .post(`/accounts/${account.accountId}/withdraw`)
        .set('Authorization', authHeader(OWNER_ID))
        .send({ amount: 4000 })

      expect(response.status).toBe(201)
      expect(response.body).toMatchObject({
        accountId: account.accountId,
        type: 'WITHDRAWAL',
        amount: { amount: 4000, currency: 'KRW' }
      })
    })

    it('when_the_account_does_not_exist_then_returns_404', async () => {
      const response = await request(app.getHttpServer())
        .post('/accounts/non-existent/withdraw')
        .set('Authorization', authHeader(OWNER_ID))
        .send({ amount: 1000 })

      expect(response.status).toBe(404)
    })

    it('when_withdrawing_more_than_the_balance_then_returns_400_and_INSUFFICIENT_BALANCE', async () => {
      const account = await createAccount()

      const response = await request(app.getHttpServer())
        .post(`/accounts/${account.accountId}/withdraw`)
        .set('Authorization', authHeader(OWNER_ID))
        .send({ amount: 1000 })

      expect(response.status).toBe(400)
      expect(response.body.code).toBe('INSUFFICIENT_BALANCE')
    })

    it('when_the_account_is_suspended_then_returns_400_and_WITHDRAW_REQUIRES_ACTIVE_ACCOUNT', async () => {
      const account = await createAccount()
      await request(app.getHttpServer())
        .post(`/accounts/${account.accountId}/suspend`)
        .set('Authorization', authHeader(OWNER_ID))

      const response = await request(app.getHttpServer())
        .post(`/accounts/${account.accountId}/withdraw`)
        .set('Authorization', authHeader(OWNER_ID))
        .send({ amount: 1000 })

      expect(response.status).toBe(400)
      expect(response.body.code).toBe('WITHDRAW_REQUIRES_ACTIVE_ACCOUNT')
    })

    it('when_the_amount_is_0_or_less_then_returns_400', async () => {
      const account = await createAccount()

      const response = await request(app.getHttpServer())
        .post(`/accounts/${account.accountId}/withdraw`)
        .set('Authorization', authHeader(OWNER_ID))
        .send({ amount: -1 })

      expect(response.status).toBe(400)
    })
  })

  describe('POST /accounts/:accountId/transfer', () => {
    it('when_the_transfer_request_is_valid_then_returns_201_and_the_withdrawal_and_deposit_transaction_details', async () => {
      const source = await createAccount(OWNER_ID)
      await request(app.getHttpServer())
        .post(`/accounts/${source.accountId}/deposit`)
        .set('Authorization', authHeader(OWNER_ID))
        .send({ amount: 10000 })
      const target = await createAccount(OTHER_OWNER_ID)

      const response = await request(app.getHttpServer())
        .post(`/accounts/${source.accountId}/transfer`)
        .set('Authorization', authHeader(OWNER_ID))
        .send({ targetAccountId: target.accountId, amount: 4000 })

      expect(response.status).toBe(201)
      expect(response.body.transferId).toEqual(expect.any(String))
      expect(response.body.sourceTransaction).toMatchObject({
        accountId: source.accountId, type: 'WITHDRAWAL', amount: { amount: 4000, currency: 'KRW' }
      })
      expect(response.body.targetTransaction).toMatchObject({
        accountId: target.accountId, type: 'DEPOSIT', amount: { amount: 4000, currency: 'KRW' }
      })

      const sourceGet = await request(app.getHttpServer())
        .get(`/accounts/${source.accountId}`)
        .set('Authorization', authHeader(OWNER_ID))
      expect(sourceGet.body.balance).toMatchObject({ amount: 6000, currency: 'KRW' })

      const targetGet = await request(app.getHttpServer())
        .get(`/accounts/${target.accountId}`)
        .set('Authorization', authHeader(OTHER_OWNER_ID))
      expect(targetGet.body.balance).toMatchObject({ amount: 4000, currency: 'KRW' })
    })

    it('can_also_transfer_to_an_account_owned_by_someone_else', async () => {
      const source = await createAccount(OWNER_ID)
      await request(app.getHttpServer())
        .post(`/accounts/${source.accountId}/deposit`)
        .set('Authorization', authHeader(OWNER_ID))
        .send({ amount: 10000 })
      const target = await createAccount(OTHER_OWNER_ID)

      const response = await request(app.getHttpServer())
        .post(`/accounts/${source.accountId}/transfer`)
        .set('Authorization', authHeader(OWNER_ID))
        .send({ targetAccountId: target.accountId, amount: 1000 })

      expect(response.status).toBe(201)
    })

    it('when_the_withdrawal_account_does_not_exist_then_returns_404_and_ACCOUNT_NOT_FOUND', async () => {
      const target = await createAccount(OTHER_OWNER_ID)

      const response = await request(app.getHttpServer())
        .post('/accounts/non-existent/transfer')
        .set('Authorization', authHeader(OWNER_ID))
        .send({ targetAccountId: target.accountId, amount: 1000 })

      expect(response.status).toBe(404)
      expect(response.body.code).toBe('ACCOUNT_NOT_FOUND')
    })

    it('when_the_deposit_account_does_not_exist_then_returns_404_and_ACCOUNT_NOT_FOUND', async () => {
      const source = await createAccount(OWNER_ID)
      await request(app.getHttpServer())
        .post(`/accounts/${source.accountId}/deposit`)
        .set('Authorization', authHeader(OWNER_ID))
        .send({ amount: 10000 })

      const response = await request(app.getHttpServer())
        .post(`/accounts/${source.accountId}/transfer`)
        .set('Authorization', authHeader(OWNER_ID))
        .send({ targetAccountId: 'non-existent', amount: 1000 })

      expect(response.status).toBe(404)
      expect(response.body.code).toBe('ACCOUNT_NOT_FOUND')
    })

    it('when_the_withdrawal_and_deposit_accounts_are_the_same_then_returns_400_and_TRANSFER_SAME_ACCOUNT', async () => {
      const account = await createAccount(OWNER_ID)
      await request(app.getHttpServer())
        .post(`/accounts/${account.accountId}/deposit`)
        .set('Authorization', authHeader(OWNER_ID))
        .send({ amount: 10000 })

      const response = await request(app.getHttpServer())
        .post(`/accounts/${account.accountId}/transfer`)
        .set('Authorization', authHeader(OWNER_ID))
        .send({ targetAccountId: account.accountId, amount: 1000 })

      expect(response.status).toBe(400)
      expect(response.body.code).toBe('TRANSFER_SAME_ACCOUNT')
    })

    it('when_transferring_more_than_the_balance_then_returns_400_and_INSUFFICIENT_BALANCE', async () => {
      const source = await createAccount(OWNER_ID)
      const target = await createAccount(OTHER_OWNER_ID)

      const response = await request(app.getHttpServer())
        .post(`/accounts/${source.accountId}/transfer`)
        .set('Authorization', authHeader(OWNER_ID))
        .send({ targetAccountId: target.accountId, amount: 1000 })

      expect(response.status).toBe(400)
      expect(response.body.code).toBe('INSUFFICIENT_BALANCE')
    })

    it('when_the_withdrawal_account_is_suspended_then_returns_400_and_WITHDRAW_REQUIRES_ACTIVE_ACCOUNT', async () => {
      const source = await createAccount(OWNER_ID)
      await request(app.getHttpServer())
        .post(`/accounts/${source.accountId}/deposit`)
        .set('Authorization', authHeader(OWNER_ID))
        .send({ amount: 10000 })
      await request(app.getHttpServer())
        .post(`/accounts/${source.accountId}/suspend`)
        .set('Authorization', authHeader(OWNER_ID))
      const target = await createAccount(OTHER_OWNER_ID)

      const response = await request(app.getHttpServer())
        .post(`/accounts/${source.accountId}/transfer`)
        .set('Authorization', authHeader(OWNER_ID))
        .send({ targetAccountId: target.accountId, amount: 1000 })

      expect(response.status).toBe(400)
      expect(response.body.code).toBe('WITHDRAW_REQUIRES_ACTIVE_ACCOUNT')
    })

    it('when_the_deposit_account_is_suspended_then_returns_400_and_DEPOSIT_REQUIRES_ACTIVE_ACCOUNT', async () => {
      const source = await createAccount(OWNER_ID)
      await request(app.getHttpServer())
        .post(`/accounts/${source.accountId}/deposit`)
        .set('Authorization', authHeader(OWNER_ID))
        .send({ amount: 10000 })
      const target = await createAccount(OTHER_OWNER_ID)
      await request(app.getHttpServer())
        .post(`/accounts/${target.accountId}/suspend`)
        .set('Authorization', authHeader(OTHER_OWNER_ID))

      const response = await request(app.getHttpServer())
        .post(`/accounts/${source.accountId}/transfer`)
        .set('Authorization', authHeader(OWNER_ID))
        .send({ targetAccountId: target.accountId, amount: 1000 })

      expect(response.status).toBe(400)
      expect(response.body.code).toBe('DEPOSIT_REQUIRES_ACTIVE_ACCOUNT')
    })

    it('when_the_currencies_do_not_match_then_returns_400_and_CURRENCY_MISMATCH', async () => {
      const source = await createAccount(OWNER_ID, 'KRW')
      await request(app.getHttpServer())
        .post(`/accounts/${source.accountId}/deposit`)
        .set('Authorization', authHeader(OWNER_ID))
        .send({ amount: 10000 })
      const target = await createAccount(OTHER_OWNER_ID, 'USD')

      const response = await request(app.getHttpServer())
        .post(`/accounts/${source.accountId}/transfer`)
        .set('Authorization', authHeader(OWNER_ID))
        .send({ targetAccountId: target.accountId, amount: 1000 })

      expect(response.status).toBe(400)
      expect(response.body.code).toBe('CURRENCY_MISMATCH')
    })
  })

  describe('POST /accounts/:accountId/suspend', () => {
    it('when_suspending_a_normal_account_then_returns_204', async () => {
      const account = await createAccount()

      const response = await request(app.getHttpServer())
        .post(`/accounts/${account.accountId}/suspend`)
        .set('Authorization', authHeader(OWNER_ID))

      expect(response.status).toBe(204)

      const getResponse = await request(app.getHttpServer())
        .get(`/accounts/${account.accountId}`)
        .set('Authorization', authHeader(OWNER_ID))
      expect(getResponse.body.status).toBe('SUSPENDED')
    })

    it('when_the_account_does_not_exist_then_returns_404', async () => {
      const response = await request(app.getHttpServer())
        .post('/accounts/non-existent/suspend')
        .set('Authorization', authHeader(OWNER_ID))

      expect(response.status).toBe(404)
    })

    it('when_the_account_is_already_suspended_then_returns_400_and_SUSPEND_REQUIRES_ACTIVE_ACCOUNT', async () => {
      const account = await createAccount()
      await request(app.getHttpServer()).post(`/accounts/${account.accountId}/suspend`).set('Authorization', authHeader(OWNER_ID))

      const response = await request(app.getHttpServer())
        .post(`/accounts/${account.accountId}/suspend`)
        .set('Authorization', authHeader(OWNER_ID))

      expect(response.status).toBe(400)
      expect(response.body.code).toBe('SUSPEND_REQUIRES_ACTIVE_ACCOUNT')
    })
  })

  describe('POST /accounts/:accountId/reactivate', () => {
    it('when_reactivating_a_suspended_account_then_returns_204', async () => {
      const account = await createAccount()
      await request(app.getHttpServer()).post(`/accounts/${account.accountId}/suspend`).set('Authorization', authHeader(OWNER_ID))

      const response = await request(app.getHttpServer())
        .post(`/accounts/${account.accountId}/reactivate`)
        .set('Authorization', authHeader(OWNER_ID))

      expect(response.status).toBe(204)

      const getResponse = await request(app.getHttpServer())
        .get(`/accounts/${account.accountId}`)
        .set('Authorization', authHeader(OWNER_ID))
      expect(getResponse.body.status).toBe('ACTIVE')
    })

    it('when_the_account_does_not_exist_then_returns_404', async () => {
      const response = await request(app.getHttpServer())
        .post('/accounts/non-existent/reactivate')
        .set('Authorization', authHeader(OWNER_ID))

      expect(response.status).toBe(404)
    })

    it('when_reactivating_an_active_account_then_returns_400_and_REACTIVATE_REQUIRES_SUSPENDED_ACCOUNT', async () => {
      const account = await createAccount()

      const response = await request(app.getHttpServer())
        .post(`/accounts/${account.accountId}/reactivate`)
        .set('Authorization', authHeader(OWNER_ID))

      expect(response.status).toBe(400)
      expect(response.body.code).toBe('REACTIVATE_REQUIRES_SUSPENDED_ACCOUNT')
    })
  })

  describe('POST /accounts/:accountId/close', () => {
    it('when_closing_an_account_with_a_0_balance_then_returns_204', async () => {
      const account = await createAccount()

      const response = await request(app.getHttpServer())
        .post(`/accounts/${account.accountId}/close`)
        .set('Authorization', authHeader(OWNER_ID))

      expect(response.status).toBe(204)

      const getResponse = await request(app.getHttpServer())
        .get(`/accounts/${account.accountId}`)
        .set('Authorization', authHeader(OWNER_ID))
      expect(getResponse.body.status).toBe('CLOSED')
    })

    it('when_the_account_does_not_exist_then_returns_404', async () => {
      const response = await request(app.getHttpServer())
        .post('/accounts/non-existent/close')
        .set('Authorization', authHeader(OWNER_ID))

      expect(response.status).toBe(404)
    })

    it('when_the_balance_is_not_0_then_returns_400_and_ACCOUNT_BALANCE_NOT_ZERO', async () => {
      const account = await createAccount()
      await request(app.getHttpServer())
        .post(`/accounts/${account.accountId}/deposit`)
        .set('Authorization', authHeader(OWNER_ID))
        .send({ amount: 5000 })

      const response = await request(app.getHttpServer())
        .post(`/accounts/${account.accountId}/close`)
        .set('Authorization', authHeader(OWNER_ID))

      expect(response.status).toBe(400)
      expect(response.body.code).toBe('ACCOUNT_BALANCE_NOT_ZERO')
    })

    it('when_the_account_is_already_closed_then_returns_400_and_ACCOUNT_ALREADY_CLOSED', async () => {
      const account = await createAccount()
      await request(app.getHttpServer()).post(`/accounts/${account.accountId}/close`).set('Authorization', authHeader(OWNER_ID))

      const response = await request(app.getHttpServer())
        .post(`/accounts/${account.accountId}/close`)
        .set('Authorization', authHeader(OWNER_ID))

      expect(response.status).toBe(400)
      expect(response.body.code).toBe('ACCOUNT_ALREADY_CLOSED')
    })
  })

  describe('GET /accounts/:accountId', () => {
    it('when_looking_up_an_existing_account_then_returns_200_and_the_account_info', async () => {
      const account = await createAccount()

      const response = await request(app.getHttpServer())
        .get(`/accounts/${account.accountId}`)
        .set('Authorization', authHeader(OWNER_ID))

      expect(response.status).toBe(200)
      expect(response.body).toMatchObject({
        accountId: account.accountId,
        ownerId: OWNER_ID,
        balance: { amount: 0, currency: 'KRW' },
        status: 'ACTIVE'
      })
      expect(response.body.updatedAt).toBeDefined()
    })

    it('when_the_account_does_not_exist_then_returns_404', async () => {
      const response = await request(app.getHttpServer())
        .get('/accounts/non-existent')
        .set('Authorization', authHeader(OWNER_ID))

      expect(response.status).toBe(404)
      expect(response.body.code).toBe('ACCOUNT_NOT_FOUND')
    })

    it('when_a_different_owner_looks_it_up_then_returns_404', async () => {
      const account = await createAccount(OWNER_ID)

      const response = await request(app.getHttpServer())
        .get(`/accounts/${account.accountId}`)
        .set('Authorization', authHeader(OTHER_OWNER_ID))

      expect(response.status).toBe(404)
    })
  })

  describe('GET /accounts/:accountId/transactions', () => {
    it('returns_the_transaction_history_with_pagination', async () => {
      const account = await createAccount()
      await request(app.getHttpServer())
        .post(`/accounts/${account.accountId}/deposit`)
        .set('Authorization', authHeader(OWNER_ID))
        .send({ amount: 10000 })
      await request(app.getHttpServer())
        .post(`/accounts/${account.accountId}/withdraw`)
        .set('Authorization', authHeader(OWNER_ID))
        .send({ amount: 3000 })

      const response = await request(app.getHttpServer())
        .get(`/accounts/${account.accountId}/transactions`)
        .set('Authorization', authHeader(OWNER_ID))
        .query({ page: 0, take: 20 })

      expect(response.status).toBe(200)
      expect(response.body.count).toBe(2)
      expect(response.body.transactions).toHaveLength(2)
      expect(response.body.transactions[0]).toHaveProperty('transactionId')
      expect(response.body.transactions[0]).toHaveProperty('type')
      expect(response.body.transactions[0]).toHaveProperty('amount')
    })

    it('when_the_account_does_not_exist_then_returns_404', async () => {
      const response = await request(app.getHttpServer())
        .get('/accounts/non-existent/transactions')
        .set('Authorization', authHeader(OWNER_ID))

      expect(response.status).toBe(404)
    })

    it('when_paging_beyond_the_available_records_then_returns_an_empty_array', async () => {
      const account = await createAccount()

      const response = await request(app.getHttpServer())
        .get(`/accounts/${account.accountId}/transactions`)
        .set('Authorization', authHeader(OWNER_ID))
        .query({ page: 5, take: 20 })

      expect(response.status).toBe(200)
      expect(response.body.transactions).toHaveLength(0)
      expect(response.body.count).toBe(0)
    })
  })
})

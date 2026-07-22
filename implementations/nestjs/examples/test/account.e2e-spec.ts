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
    it('생성_요청이_유효하면_201과_계좌_정보를_반환한다', async () => {
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

    it('currency가_없으면_400과_VALIDATION_FAILED를_반환한다', async () => {
      const response = await request(app.getHttpServer())
        .post('/accounts')
        .set('Authorization', authHeader(OWNER_ID))
        .send({ email: 'owner1@example.com' })

      expect(response.status).toBe(400)
      expect(response.body.code).toBe('VALIDATION_FAILED')
    })

    it('email이_유효하지_않으면_400과_VALIDATION_FAILED를_반환한다', async () => {
      const response = await request(app.getHttpServer())
        .post('/accounts')
        .set('Authorization', authHeader(OWNER_ID))
        .send({ currency: 'KRW', email: 'not-an-email' })

      expect(response.status).toBe(400)
      expect(response.body.code).toBe('VALIDATION_FAILED')
    })
  })

  describe('POST /accounts/:accountId/deposit', () => {
    it('입금_요청이_유효하면_201과_거래_내역을_반환한다', async () => {
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

    it('존재하지_않는_계좌면_404와_ACCOUNT_NOT_FOUND를_반환한다', async () => {
      const response = await request(app.getHttpServer())
        .post('/accounts/non-existent/deposit')
        .set('Authorization', authHeader(OWNER_ID))
        .send({ amount: 10000 })

      expect(response.status).toBe(404)
      expect(response.body.code).toBe('ACCOUNT_NOT_FOUND')
    })

    it('다른_소유자의_계좌면_404를_반환한다', async () => {
      const account = await createAccount(OWNER_ID)

      const response = await request(app.getHttpServer())
        .post(`/accounts/${account.accountId}/deposit`)
        .set('Authorization', authHeader(OTHER_OWNER_ID))
        .send({ amount: 10000 })

      expect(response.status).toBe(404)
    })

    it('금액이_0_이하이면_400을_반환한다', async () => {
      const account = await createAccount()

      const response = await request(app.getHttpServer())
        .post(`/accounts/${account.accountId}/deposit`)
        .set('Authorization', authHeader(OWNER_ID))
        .send({ amount: 0 })

      expect(response.status).toBe(400)
    })

    it('정지된_계좌면_400과_DEPOSIT_REQUIRES_ACTIVE_ACCOUNT를_반환한다', async () => {
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
    it('출금_요청이_유효하면_201과_거래_내역을_반환한다', async () => {
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

    it('존재하지_않는_계좌면_404를_반환한다', async () => {
      const response = await request(app.getHttpServer())
        .post('/accounts/non-existent/withdraw')
        .set('Authorization', authHeader(OWNER_ID))
        .send({ amount: 1000 })

      expect(response.status).toBe(404)
    })

    it('잔액보다_큰_금액을_출금하면_400과_INSUFFICIENT_BALANCE를_반환한다', async () => {
      const account = await createAccount()

      const response = await request(app.getHttpServer())
        .post(`/accounts/${account.accountId}/withdraw`)
        .set('Authorization', authHeader(OWNER_ID))
        .send({ amount: 1000 })

      expect(response.status).toBe(400)
      expect(response.body.code).toBe('INSUFFICIENT_BALANCE')
    })

    it('정지된_계좌면_400과_WITHDRAW_REQUIRES_ACTIVE_ACCOUNT를_반환한다', async () => {
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

    it('금액이_0_이하이면_400을_반환한다', async () => {
      const account = await createAccount()

      const response = await request(app.getHttpServer())
        .post(`/accounts/${account.accountId}/withdraw`)
        .set('Authorization', authHeader(OWNER_ID))
        .send({ amount: -1 })

      expect(response.status).toBe(400)
    })
  })

  describe('POST /accounts/:accountId/transfer', () => {
    it('송금_요청이_유효하면_201과_출금_입금_거래_내역을_반환한다', async () => {
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

    it('타인_소유_계좌로도_송금할_수_있다', async () => {
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

    it('존재하지_않는_출금_계좌면_404와_ACCOUNT_NOT_FOUND를_반환한다', async () => {
      const target = await createAccount(OTHER_OWNER_ID)

      const response = await request(app.getHttpServer())
        .post('/accounts/non-existent/transfer')
        .set('Authorization', authHeader(OWNER_ID))
        .send({ targetAccountId: target.accountId, amount: 1000 })

      expect(response.status).toBe(404)
      expect(response.body.code).toBe('ACCOUNT_NOT_FOUND')
    })

    it('존재하지_않는_입금_계좌면_404와_ACCOUNT_NOT_FOUND를_반환한다', async () => {
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

    it('출금_계좌와_입금_계좌가_같으면_400과_TRANSFER_SAME_ACCOUNT를_반환한다', async () => {
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

    it('잔액보다_큰_금액을_송금하면_400과_INSUFFICIENT_BALANCE를_반환한다', async () => {
      const source = await createAccount(OWNER_ID)
      const target = await createAccount(OTHER_OWNER_ID)

      const response = await request(app.getHttpServer())
        .post(`/accounts/${source.accountId}/transfer`)
        .set('Authorization', authHeader(OWNER_ID))
        .send({ targetAccountId: target.accountId, amount: 1000 })

      expect(response.status).toBe(400)
      expect(response.body.code).toBe('INSUFFICIENT_BALANCE')
    })

    it('출금_계좌가_정지_상태면_400과_WITHDRAW_REQUIRES_ACTIVE_ACCOUNT를_반환한다', async () => {
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

    it('입금_계좌가_정지_상태면_400과_DEPOSIT_REQUIRES_ACTIVE_ACCOUNT를_반환한다', async () => {
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

    it('통화가_일치하지_않으면_400과_CURRENCY_MISMATCH를_반환한다', async () => {
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
    it('정상_계좌를_정지하면_204를_반환한다', async () => {
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

    it('존재하지_않는_계좌면_404를_반환한다', async () => {
      const response = await request(app.getHttpServer())
        .post('/accounts/non-existent/suspend')
        .set('Authorization', authHeader(OWNER_ID))

      expect(response.status).toBe(404)
    })

    it('이미_정지된_계좌면_400과_SUSPEND_REQUIRES_ACTIVE_ACCOUNT를_반환한다', async () => {
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
    it('정지된_계좌를_재개하면_204를_반환한다', async () => {
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

    it('존재하지_않는_계좌면_404를_반환한다', async () => {
      const response = await request(app.getHttpServer())
        .post('/accounts/non-existent/reactivate')
        .set('Authorization', authHeader(OWNER_ID))

      expect(response.status).toBe(404)
    })

    it('활성_계좌를_재개하면_400과_REACTIVATE_REQUIRES_SUSPENDED_ACCOUNT를_반환한다', async () => {
      const account = await createAccount()

      const response = await request(app.getHttpServer())
        .post(`/accounts/${account.accountId}/reactivate`)
        .set('Authorization', authHeader(OWNER_ID))

      expect(response.status).toBe(400)
      expect(response.body.code).toBe('REACTIVATE_REQUIRES_SUSPENDED_ACCOUNT')
    })
  })

  describe('POST /accounts/:accountId/close', () => {
    it('잔액이_0인_계좌를_종료하면_204를_반환한다', async () => {
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

    it('존재하지_않는_계좌면_404를_반환한다', async () => {
      const response = await request(app.getHttpServer())
        .post('/accounts/non-existent/close')
        .set('Authorization', authHeader(OWNER_ID))

      expect(response.status).toBe(404)
    })

    it('잔액이_0이_아니면_400과_ACCOUNT_BALANCE_NOT_ZERO를_반환한다', async () => {
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

    it('이미_종료된_계좌면_400과_ACCOUNT_ALREADY_CLOSED를_반환한다', async () => {
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
    it('존재하는_계좌를_조회하면_200과_계좌_정보를_반환한다', async () => {
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

    it('존재하지_않는_계좌면_404를_반환한다', async () => {
      const response = await request(app.getHttpServer())
        .get('/accounts/non-existent')
        .set('Authorization', authHeader(OWNER_ID))

      expect(response.status).toBe(404)
      expect(response.body.code).toBe('ACCOUNT_NOT_FOUND')
    })

    it('다른_소유자가_조회하면_404를_반환한다', async () => {
      const account = await createAccount(OWNER_ID)

      const response = await request(app.getHttpServer())
        .get(`/accounts/${account.accountId}`)
        .set('Authorization', authHeader(OTHER_OWNER_ID))

      expect(response.status).toBe(404)
    })
  })

  describe('GET /accounts/:accountId/transactions', () => {
    it('거래_내역을_페이지네이션과_함께_반환한다', async () => {
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

    it('존재하지_않는_계좌면_404를_반환한다', async () => {
      const response = await request(app.getHttpServer())
        .get('/accounts/non-existent/transactions')
        .set('Authorization', authHeader(OWNER_ID))

      expect(response.status).toBe(404)
    })

    it('take를_초과한_페이지_조회는_빈_배열을_반환한다', async () => {
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

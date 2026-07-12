import { BadRequestException, INestApplication, ValidationPipe } from '@nestjs/common'
import { ConfigModule } from '@nestjs/config'
import { Test } from '@nestjs/testing'
import { TypeOrmModule } from '@nestjs/typeorm'
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

describe('AccountController (e2e)', () => {
  let container: StartedPostgreSqlContainer
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

    const moduleRef = await Test.createTestingModule({
      imports: [
        ConfigModule.forRoot({ isGlobal: true, load: [jwtConfig] }),
        TypeOrmModule.forRoot({
          type: 'postgres',
          url: container.getConnectionUri(),
          entities: [AccountEntity, TransactionEntity, OutboxEntity, SentEmailEntity, CredentialEntity],
          synchronize: true
        }),
        OutboxModule,
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
    await app?.close()
    await container?.stop()
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

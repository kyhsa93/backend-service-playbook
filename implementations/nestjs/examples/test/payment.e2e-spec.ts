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

// Payment BC의 3자 조율(Card+Account 동기 Adapter)과, RefundEligibilityService
// (Domain Service, Payment+Refund 두 Aggregate를 조율하는 순수 판단 로직), 그리고
// Payment↔Account 양방향 Integration Event(결제완료→차감, 결제취소/환불승인→보상 크레딧)
// 를 실제 앱 부팅 + Postgres로 검증한다. card.e2e-spec.ts와 동일한 패턴을 따른다.
describe('PaymentController (e2e) — Payment/Refund + Card/Account 크로스 도메인', () => {
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

  // Card는 계좌 정지(account.suspended.v1)에 비동기로 반응해서만 SUSPENDED가 된다
  // (카드 자체를 정지시키는 전용 엔드포인트는 없다) — card.e2e-spec.ts와 동일한 폴링 패턴.
  // Outbox 드레인이 OutboxPoller(1초 주기)→SQS→OutboxConsumer(long polling)를 거치는
  // 비동기 경로라 완료까지 지연이 있을 수 있어 150회 * 200ms(최대 30초)의 폴링 예산을 둔다.
  async function waitForCardStatus(cardId: string, expected: string, ownerId = OWNER_ID): Promise<string> {
    for (let i = 0; i < 150; i++) {
      const status = await getCardStatus(cardId, ownerId)
      if (status === expected) return status
      await new Promise((resolve) => setTimeout(resolve, 200))
    }
    return getCardStatus(cardId, ownerId)
  }

  // card.e2e-spec.ts의 waitForCardStatus와 동일한 이유로 폴링 예산을 늘린다.
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
      // SES(기술 서비스)는 이 테스트 관심사가 아니므로 no-op으로 대체한다.
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

  describe('POST /payments — 동기 Adapter(ACL)로 Card/Account 상태 확인', () => {
    it('비활성_카드로_결제하면_400과_PAYMENT_REQUIRES_ACTIVE_CARD를_반환한다', async () => {
      const account = await createAccount()
      await deposit(account.accountId, 50000)
      const card = await issueCard(account.accountId)
      // 카드를 정지시키는 카드 전용 엔드포인트는 없으므로(카드는 계좌 정지에 반응해서만
      // SUSPENDED가 된다), 계좌를 정지시켜 연결 카드가 비동기로 SUSPENDED 전환되게 한다.
      await request(app.getHttpServer())
        .post(`/accounts/${account.accountId}/suspend`)
        .set('Authorization', authHeader(OWNER_ID))
      expect(await waitForCardStatus(card.cardId, 'SUSPENDED')).toBe('SUSPENDED')

      const response = await createPayment(card.cardId, 10000)

      expect(response.status).toBe(400)
      expect(response.body.code).toBe('PAYMENT_REQUIRES_ACTIVE_CARD')
    })

    it('잔액이_부족하면_400과_INSUFFICIENT_BALANCE를_반환한다', async () => {
      const account = await createAccount()
      await deposit(account.accountId, 1000)
      const card = await issueCard(account.accountId)

      const response = await createPayment(card.cardId, 5000)

      expect(response.status).toBe(400)
      expect(response.body.code).toBe('INSUFFICIENT_BALANCE')
    })

    it('존재하지_않는_카드면_404와_LINKED_CARD_NOT_FOUND를_반환한다', async () => {
      const response = await createPayment('non-existent-card', 1000)

      expect(response.status).toBe(404)
      expect(response.body.code).toBe('LINKED_CARD_NOT_FOUND')
    })

    it('다른_소유자의_카드면_404를_반환한다', async () => {
      const account = await createAccount(OWNER_ID)
      await deposit(account.accountId, 50000)
      const card = await issueCard(account.accountId, OWNER_ID)

      const response = await createPayment(card.cardId, 1000, OTHER_OWNER_ID)

      expect(response.status).toBe(404)
      expect(response.body.code).toBe('LINKED_CARD_NOT_FOUND')
    })

    it('활성_카드와_충분한_잔액이면_201과_COMPLETED_결제를_반환하고_비동기로_계좌_잔액이_차감된다', async () => {
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

      // 동기 Adapter로 판정만 하고, 실제 차감은 payment.completed.v1을 Account BC가
      // 구독해 비동기로 수행한다 — 그 결과를 폴링으로 기다린다.
      expect(await waitForBalance(account.accountId, 40000)).toBe(40000)
    })
  })

  describe('POST /payments/:paymentId/cancel — 결제취소 → 보상 크레딧', () => {
    it('완료된_결제를_취소하면_204를_반환하고_비동기로_계좌_잔액이_복구된다', async () => {
      const account = await createAccount()
      await deposit(account.accountId, 50000)
      const card = await issueCard(account.accountId)
      const payment = (await createPayment(card.cardId, 10000)).body as { paymentId: string }
      await waitForBalance(account.accountId, 40000)

      const cancelResponse = await request(app.getHttpServer())
        .post(`/payments/${payment.paymentId}/cancel`)
        .set('Authorization', authHeader(OWNER_ID))
        .send({ reason: '고객 요청' })

      expect(cancelResponse.status).toBe(204)
      expect(await waitForBalance(account.accountId, 50000)).toBe(50000)

      const getResponse = await request(app.getHttpServer())
        .get(`/payments/${payment.paymentId}`)
        .set('Authorization', authHeader(OWNER_ID))
      expect(getResponse.body.status).toBe('CANCELLED')
    })

    it('존재하지_않는_결제면_404를_반환한다', async () => {
      const response = await request(app.getHttpServer())
        .post('/payments/non-existent/cancel')
        .set('Authorization', authHeader(OWNER_ID))
        .send({ reason: '사유' })

      expect(response.status).toBe(404)
      expect(response.body.code).toBe('PAYMENT_NOT_FOUND')
    })

    it('이미_취소된_결제를_다시_취소하면_400과_PAYMENT_CANCEL_REQUIRES_COMPLETED_PAYMENT를_반환한다', async () => {
      const account = await createAccount()
      await deposit(account.accountId, 50000)
      const card = await issueCard(account.accountId)
      const payment = (await createPayment(card.cardId, 10000)).body as { paymentId: string }
      await request(app.getHttpServer())
        .post(`/payments/${payment.paymentId}/cancel`)
        .set('Authorization', authHeader(OWNER_ID))
        .send({ reason: '고객 요청' })

      const response = await request(app.getHttpServer())
        .post(`/payments/${payment.paymentId}/cancel`)
        .set('Authorization', authHeader(OWNER_ID))
        .send({ reason: '고객 요청' })

      expect(response.status).toBe(400)
      expect(response.body.code).toBe('PAYMENT_CANCEL_REQUIRES_COMPLETED_PAYMENT')
    })
  })

  describe('POST /payments/:paymentId/refunds — RefundEligibilityService(Domain Service) 판단', () => {
    it('환불_금액이_결제_금액을_초과하면_201과_REJECTED_상태를_반환하고_계좌는_크레딧되지_않는다', async () => {
      const account = await createAccount()
      await deposit(account.accountId, 50000)
      const card = await issueCard(account.accountId)
      const payment = (await createPayment(card.cardId, 10000)).body as { paymentId: string }
      await waitForBalance(account.accountId, 40000)

      const response = await request(app.getHttpServer())
        .post(`/payments/${payment.paymentId}/refunds`)
        .set('Authorization', authHeader(OWNER_ID))
        .send({ amount: 20000, reason: '상품 불량' })

      expect(response.status).toBe(201)
      expect(response.body.status).toBe('REJECTED')
      expect(response.body.decisionNote).toBe('환불 금액은 결제 금액을 초과할 수 없습니다.')

      // 거부된 환불은 Domain Event가 없으므로 드레인할 것이 없다 — 잔액은 그대로다.
      await new Promise((resolve) => setTimeout(resolve, 300))
      expect(await getBalance(account.accountId)).toBe(40000)
    })

    it('완료되지_않은_결제(취소된_결제)에_환불을_요청하면_201과_REJECTED_상태를_반환한다', async () => {
      const account = await createAccount()
      await deposit(account.accountId, 50000)
      const card = await issueCard(account.accountId)
      const payment = (await createPayment(card.cardId, 10000)).body as { paymentId: string }
      await waitForBalance(account.accountId, 40000)
      await request(app.getHttpServer())
        .post(`/payments/${payment.paymentId}/cancel`)
        .set('Authorization', authHeader(OWNER_ID))
        .send({ reason: '고객 요청' })
      await waitForBalance(account.accountId, 50000)

      const response = await request(app.getHttpServer())
        .post(`/payments/${payment.paymentId}/refunds`)
        .set('Authorization', authHeader(OWNER_ID))
        .send({ amount: 5000, reason: '상품 불량' })

      expect(response.status).toBe(201)
      expect(response.body.status).toBe('REJECTED')
      expect(response.body.decisionNote).toBe('완료된 결제에 대해서만 환불을 요청할 수 있습니다.')
    })

    it('유효한_환불_요청이면_201과_APPROVED_상태를_반환하고_비동기로_계좌가_크레딧된다', async () => {
      const account = await createAccount()
      await deposit(account.accountId, 50000)
      const card = await issueCard(account.accountId)
      const payment = (await createPayment(card.cardId, 10000)).body as { paymentId: string }
      await waitForBalance(account.accountId, 40000)

      const response = await request(app.getHttpServer())
        .post(`/payments/${payment.paymentId}/refunds`)
        .set('Authorization', authHeader(OWNER_ID))
        .send({ amount: 4000, reason: '부분 환불' })

      expect(response.status).toBe(201)
      expect(response.body.status).toBe('APPROVED')
      expect(await waitForBalance(account.accountId, 44000)).toBe(44000)

      const listResponse = await request(app.getHttpServer())
        .get(`/payments/${payment.paymentId}/refunds`)
        .set('Authorization', authHeader(OWNER_ID))
      expect(listResponse.body.count).toBe(1)
      expect(listResponse.body.refunds[0].status).toBe('APPROVED')
    })

    it('존재하지_않는_결제면_404를_반환한다', async () => {
      const response = await request(app.getHttpServer())
        .post('/payments/non-existent/refunds')
        .set('Authorization', authHeader(OWNER_ID))
        .send({ amount: 1000, reason: '사유' })

      expect(response.status).toBe(404)
      expect(response.body.code).toBe('PAYMENT_NOT_FOUND')
    })
  })

  describe('GET /payments, GET /payments/:paymentId', () => {
    it('내_결제_내역을_페이지네이션과_함께_반환한다', async () => {
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

    it('다른_소유자의_결제를_조회하면_404를_반환한다', async () => {
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

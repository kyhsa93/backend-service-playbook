import { BadRequestException, INestApplication, ValidationPipe } from '@nestjs/common'
import { ConfigModule } from '@nestjs/config'
import { Test } from '@nestjs/testing'
import { TypeOrmModule } from '@nestjs/typeorm'
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

describe('CardController (e2e) — 크로스 도메인 Account↔Card', () => {
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

  // in-process Outbox 드레인은 커맨드 처리 안에서 완결되지만, 혹시 모를 스케줄링 편차를
  // 흡수하도록 짧게 재시도하며 카드 상태 전이를 기다린다.
  async function waitForCardStatus(cardId: string, expected: string, ownerId = OWNER_ID): Promise<string> {
    for (let i = 0; i < 20; i++) {
      const status = await getCardStatus(cardId, ownerId)
      if (status === expected) return status
      await new Promise((resolve) => setTimeout(resolve, 100))
    }
    return getCardStatus(cardId, ownerId)
  }

  beforeAll(async () => {
    container = await new PostgreSqlContainer('postgres:16-alpine').start()

    const moduleRef = await Test.createTestingModule({
      imports: [
        ConfigModule.forRoot({ isGlobal: true, load: [jwtConfig] }),
        TypeOrmModule.forRoot({
          type: 'postgres',
          url: container.getConnectionUri(),
          entities: [AccountEntity, TransactionEntity, CardEntity, OutboxEntity, SentEmailEntity, CredentialEntity],
          synchronize: true
        }),
        OutboxModule,
        AuthModule,
        AccountModule,
        CardModule
      ]
    })
      // SES(기술 서비스)는 이 테스트 관심사가 아니므로 no-op으로 대체한다. 이렇게 하면
      // 계좌 이벤트 핸들러가 이메일 실패로 throw하지 않고 Integration Event 전달만 검증한다.
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
    await app?.close()
    await container?.stop()
  })

  describe('POST /cards — 동기 Adapter(ACL)로 Account 상태 확인', () => {
    it('활성_계좌에_카드를_발급하면_201과_ACTIVE_카드를_반환한다', async () => {
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

    it('존재하지_않는_계좌면_404와_LINKED_ACCOUNT_NOT_FOUND를_반환한다', async () => {
      const response = await issueCard('non-existent-account')

      expect(response.status).toBe(404)
      expect(response.body.code).toBe('LINKED_ACCOUNT_NOT_FOUND')
    })

    it('다른_소유자의_계좌면_404를_반환한다', async () => {
      const account = await createAccount(OWNER_ID)

      const response = await issueCard(account.accountId, OTHER_OWNER_ID)

      expect(response.status).toBe(404)
      expect(response.body.code).toBe('LINKED_ACCOUNT_NOT_FOUND')
    })

    it('정지된_계좌면_400과_CARD_ISSUE_REQUIRES_ACTIVE_ACCOUNT를_반환한다', async () => {
      const account = await createAccount()
      await request(app.getHttpServer())
        .post(`/accounts/${account.accountId}/suspend`)
        .set('Authorization', authHeader(OWNER_ID))

      const response = await issueCard(account.accountId)

      expect(response.status).toBe(400)
      expect(response.body.code).toBe('CARD_ISSUE_REQUIRES_ACTIVE_ACCOUNT')
    })
  })

  describe('비동기 Integration Event — Account 상태 변화에 Card가 반응', () => {
    it('계좌가_정지되면_연결된_카드가_SUSPENDED로_전환된다', async () => {
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

    it('계좌가_해지되면_연결된_카드가_CANCELLED로_전환된다', async () => {
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

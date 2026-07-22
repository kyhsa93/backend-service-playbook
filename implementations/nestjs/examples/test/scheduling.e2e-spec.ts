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
import { AccountInterestScheduler } from '@/account/infrastructure/account-interest-scheduler'
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
import { computePreviousStatementMonth } from '@/payment/infrastructure/previous-statement-month'
import { CardStatementScheduler } from '@/payment/infrastructure/card-statement-scheduler'
import { PaymentEntity } from '@/payment/infrastructure/entity/payment.entity'
import { RefundEntity } from '@/payment/infrastructure/entity/refund.entity'
import { SentCardStatementEntity } from '@/payment/infrastructure/notification/sent-card-statement.entity'
import { PaymentModule } from '@/payment/payment-module'
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

// Verifies the Scheduling (Task Queue) infrastructure for real — that Cron never executes
// directly and only calls TaskQueue.enqueue, that the entire path task_outbox → SQS FIFO
// (TaskOutboxRelay) → Consumer → Task Controller → Command Handler actually works, and
// confirms the Level 1 idempotency of each of the two batch Tasks (daily interest payment,
// monthly card-statement sending).
//
// Instead of waiting for a real Cron tick (midnight/the 1st of the month), it calls the
// Scheduler's enqueue method directly, the same way existing e2e tests like card.e2e-spec.ts
// do (since scheduling.md already states "the Scheduler only enqueues," the enqueue method
// itself is the Cron handler's entire responsibility — there's no separately hidden logic).
describe('Scheduling(Task Queue) — 일 이자 지급 / 월간 카드 사용내역 발송 (e2e)', () => {
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
          entities: [
            AccountEntity, TransactionEntity, SentEmailEntity, CredentialEntity,
            CardEntity, PaymentEntity, RefundEntity, SentCardStatementEntity,
            OutboxEntity, TaskOutboxEntity
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

  async function createAccount(): Promise<string> {
    const response = await request(app.getHttpServer())
      .post('/accounts')
      .set('Authorization', `Bearer ${ownerToken}`)
      .send({ currency: 'KRW', email: RECIPIENT_EMAIL })
    return (response.body as { accountId: string }).accountId
  }

  async function deposit(accountId: string, amount: number): Promise<void> {
    await request(app.getHttpServer())
      .post(`/accounts/${accountId}/deposit`)
      .set('Authorization', `Bearer ${ownerToken}`)
      .send({ amount })
  }

  async function getBalance(accountId: string): Promise<number> {
    const response = await request(app.getHttpServer())
      .get(`/accounts/${accountId}`)
      .set('Authorization', `Bearer ${ownerToken}`)
    return response.body.balance.amount
  }

  // Goes through the entire path Task Outbox → Relay (3-second polling) → SQS FIFO →
  // TaskQueueConsumer → Task Controller → Command Handler, so instead of finishing immediately
  // in the same process, it waits via polling (the same pattern as card.e2e-spec.ts's waitForCardStatus).
  async function waitForBalance(accountId: string, expected: number): Promise<number> {
    for (let i = 0; i < 150; i++) {
      const balance = await getBalance(accountId)
      if (balance === expected) return balance
      await new Promise((resolve) => setTimeout(resolve, 200))
    }
    return getBalance(accountId)
  }

  describe('일 이자 지급 (account.apply-daily-interest)', () => {
    it('이자_지급_Task가_적재되면_ACTIVE_계좌_잔액에_이자가_반영되고_같은_날_재적재해도_중복_지급되지_않는다', async () => {
      const accountId = await createAccount()
      await deposit(accountId, 1_000_000)
      expect(await getBalance(accountId)).toBe(1_000_000)

      const scheduler = app.get(AccountInterestScheduler)
      await scheduler.enqueueDailyInterest()

      // 1_000_000 × 0.0001(DAILY_INTEREST_RATE) = 100
      const balanceAfterInterest = await waitForBalance(accountId, 1_000_100)
      expect(balanceAfterInterest).toBe(1_000_100)

      const account = await dataSource.getRepository(AccountEntity).findOneBy({ accountId })
      expect(account?.lastInterestPaidAt).not.toBeNull()

      const transactions = await dataSource.getRepository(TransactionEntity).findBy({ accountId, type: 'INTEREST' })
      expect(transactions).toHaveLength(1)
      expect(transactions[0].amount).toBe(100)

      // Since it's the same date's dedupId, even if the second enqueue is re-received (Level 1 idempotency), the balance must not double-increment.
      await scheduler.enqueueDailyInterest()
      await new Promise((resolve) => setTimeout(resolve, 5000))
      expect(await getBalance(accountId)).toBe(1_000_100)
    }, 60000)
  })

  describe('월간 카드 사용내역 발송 (payment.send-card-statements)', () => {
    it('지난_달_결제내역이_있는_ACTIVE_카드에_사용내역_이메일이_발송되고_같은_달_재적재해도_중복_발송되지_않는다', async () => {
      const accountId = await createAccount()
      await deposit(accountId, 1_000_000)

      const cardResponse = await request(app.getHttpServer())
        .post('/cards')
        .set('Authorization', `Bearer ${ownerToken}`)
        .send({ accountId, brand: 'VISA' })
      const cardId = (cardResponse.body as { cardId: string }).cardId

      const payment1 = await request(app.getHttpServer())
        .post('/payments')
        .set('Authorization', `Bearer ${ownerToken}`)
        .send({ cardId, amount: 10000 })
      const payment2 = await request(app.getHttpServer())
        .post('/payments')
        .set('Authorization', `Bearer ${ownerToken}`)
        .send({ cardId, amount: 5000 })

      // Reuses the exact same computation as the statistics' target period ("last month") to
      // backdate the payment timestamp into that range — the statistics only line up if it
      // matches the logic the Scheduler actually computes with (so the logic itself isn't
      // changed for test convenience).
      const { statementMonth, monthStart } = computePreviousStatementMonth(new Date())
      const backdatedAt = new Date(monthStart.getTime() + 24 * 60 * 60 * 1000)
      await dataSource.getRepository(PaymentEntity).update(
        { paymentId: payment1.body.paymentId as string },
        { createdAt: backdatedAt }
      )
      await dataSource.getRepository(PaymentEntity).update(
        { paymentId: payment2.body.paymentId as string },
        { createdAt: backdatedAt }
      )

      const scheduler = app.get(CardStatementScheduler)
      await scheduler.enqueueMonthlyCardStatements()

      const statementRepo = dataSource.getRepository(SentCardStatementEntity)
      let sentStatement: SentCardStatementEntity | null = null
      for (let i = 0; i < 150; i++) {
        sentStatement = await statementRepo.findOneBy({ cardId, statementMonth })
        if (sentStatement) break
        await new Promise((resolve) => setTimeout(resolve, 200))
      }

      expect(sentStatement).not.toBeNull()
      expect(sentStatement?.paymentCount).toBe(2)
      expect(sentStatement?.totalAmount).toBe(15000)
      expect(sentStatement?.recipient).toBe(RECIPIENT_EMAIL)

      const sesMessages = await fetchSesMessages(sesEndpoint)
      const matched = sesMessages.find((message) => message.Id === sentStatement?.sesMessageId)
      expect(matched).toBeDefined()
      expect(matched?.Destination.ToAddresses).toContain(RECIPIENT_EMAIL)

      // Since it's the same month's dedupId, even if the second enqueue is reprocessed, the
      // (cardId, statementMonth) unique constraint + the hasSentStatement precheck must prevent a duplicate row.
      await scheduler.enqueueMonthlyCardStatements()
      await new Promise((resolve) => setTimeout(resolve, 5000))
      const allStatementsForCard = await statementRepo.findBy({ cardId, statementMonth })
      expect(allStatementsForCard).toHaveLength(1)
    }, 60000)
  })
})

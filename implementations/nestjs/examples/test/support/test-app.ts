import { INestApplication } from '@nestjs/common'
import { Test } from '@nestjs/testing'
import { SESClient, VerifyEmailIdentityCommand } from '@aws-sdk/client-ses'
import { LocalstackContainer } from '@testcontainers/localstack'
import { PostgreSqlContainer } from '@testcontainers/postgresql'
import { DataSource } from 'typeorm'

import { FAKE_OLLAMA_ORIGIN } from './ollama-stub'
import { createDomainEventQueue } from './sqs-test-queue'
import { createTaskQueue } from './task-queue-test-queue'

// Boots the REAL application for an E2E spec: the actual AppModule (including the migrations
// run TypeORM performs via migrationsRun: true — no synchronize, no hand-picked entity list)
// plus the exact app-object setup production applies (src/app-setup.ts). Each spec still owns
// its containers: one disposable PostgreSQL and one LocalStack (SQS + SES) per spec file.

export const SES_SENDER_EMAIL = 'no-reply@backend-service-playbook.example.com'

const MANAGED_ENV_KEYS = [
  'DATABASE_URL',
  'AWS_ENDPOINT_URL',
  'AWS_REGION',
  'AWS_ACCESS_KEY_ID',
  'AWS_SECRET_ACCESS_KEY',
  'SQS_DOMAIN_EVENT_QUEUE_URL',
  'SQS_TASK_QUEUE_URL',
  'SES_SENDER_EMAIL',
  'JWT_SECRET',
  'JWT_EXPIRES_IN',
  'OLLAMA_BASE_URL',
  'THROTTLE_SHORT_LIMIT',
  'THROTTLE_MEDIUM_LIMIT',
  'THROTTLE_LONG_LIMIT'
] as const

export interface SesMessage {
  Id: string
  Source: string
  Destination: { ToAddresses: string[] }
  Subject: string
}

// LocalStack records every SES send at this internal endpoint — real sent-email verification.
export async function fetchSesMessages(endpoint: string): Promise<SesMessage[]> {
  const response = await fetch(`${endpoint}/_aws/ses`)
  const body = (await response.json()) as { messages: SesMessage[] }
  return body.messages
}

export interface StartedTestApp {
  app: INestApplication
  dataSource: DataSource
  // The LocalStack edge URL — SQS/SES endpoint, also serves the /_aws/ses send log.
  awsEndpoint: string
  stop: () => Promise<void>
}

export async function startTestApp(): Promise<StartedTestApp> {
  const postgres = await new PostgreSqlContainer('postgres:16-alpine').start()
  const localstack = await new LocalstackContainer('localstack/localstack:3.0')
    .withEnvironment({ SERVICES: 'sqs,ses' })
    .start()
  const awsEndpoint = localstack.getConnectionUri()

  // Everything the real app reads from the environment, set BEFORE AppModule is imported —
  // src/database/data-source.ts calls getDatabaseUrl() at module-evaluation time, and
  // app-module.ts evaluates getThrottlerConfig() while its @Module decorator runs.
  process.env.DATABASE_URL = postgres.getConnectionUri()
  process.env.AWS_ENDPOINT_URL = awsEndpoint
  process.env.AWS_REGION = 'us-east-1'
  process.env.AWS_ACCESS_KEY_ID = 'test'
  process.env.AWS_SECRET_ACCESS_KEY = 'test'
  process.env.SQS_DOMAIN_EVENT_QUEUE_URL = await createDomainEventQueue(awsEndpoint)
  process.env.SQS_TASK_QUEUE_URL = await createTaskQueue(awsEndpoint)
  process.env.SES_SENDER_EMAIL = SES_SENDER_EMAIL
  process.env.JWT_SECRET = 'e2e-test-secret'
  process.env.JWT_EXPIRES_IN = '1h'
  // A fake origin nock intercepts (see ollama-stub.ts) — never a real Ollama.
  process.env.OLLAMA_BASE_URL = FAKE_OLLAMA_ORIGIN
  // The real AppModule registers the production ThrottlerGuard globally. E2E polling loops
  // (5 requests/second while waiting on async outcomes) would trip the production-sized
  // limits, so raise them through the same THROTTLE_* variables production operators use.
  process.env.THROTTLE_SHORT_LIMIT = '1000000'
  process.env.THROTTLE_MEDIUM_LIMIT = '1000000'
  process.env.THROTTLE_LONG_LIMIT = '1000000'

  // SES refuses to send from an unverified identity — verify the sender like production would.
  const verificationClient = new SESClient({
    region: 'us-east-1',
    endpoint: awsEndpoint,
    credentials: { accessKeyId: 'test', secretAccessKey: 'test' }
  })
  await verificationClient.send(new VerifyEmailIdentityCommand({ EmailAddress: SES_SENDER_EMAIL }))

  // Dynamic imports on purpose: each Jest test file has its own module registry, so importing
  // here — after the environment above is fully populated — is what lets data-source.ts (and
  // every config read that happens at import/decorator time) see the containers' real values.
  const { AppModule } = await import('@/app-module')
  const { configureApp } = await import('@/app-setup')

  const moduleRef = await Test.createTestingModule({ imports: [AppModule] }).compile()
  const app = moduleRef.createNestApplication({ logger: ['error', 'warn'] })
  configureApp(app)
  await app.init()

  return {
    app,
    dataSource: app.get(DataSource),
    awsEndpoint,
    stop: async () => {
      // app.close() first — it runs OutboxModule/TaskQueueModule OnModuleDestroy hooks, which
      // is what stops the background SQS poll loops. Stopping the containers first leaves
      // those loops retrying against dead endpoints forever and hangs Jest.
      await app.close()
      await postgres.stop()
      await localstack.stop()
      for (const key of MANAGED_ENV_KEYS) delete process.env[key]
    }
  }
}

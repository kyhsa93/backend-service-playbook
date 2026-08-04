import { INestApplication } from '@nestjs/common'
import nock from 'nock'
import request from 'supertest'
import { DataSource } from 'typeorm'

import { SentEmailEntity } from '@/account/infrastructure/notification/sent-email.entity'
import {
  FAKE_OLLAMA_ORIGIN,
  OllamaChatRequestBody,
  isAnswerComposerRequest,
  isAutoCategorizerRequest,
  isQueryTranslatorRequest,
  ollamaChatReply,
  userPrompt
} from './support/ollama-stub'
import { StartedTestApp, startTestApp } from './support/test-app'

// Boots the REAL AppModule (see test/support/test-app.ts): real migrations, the real Outbox
// pipeline against LocalStack SQS, real SES sends, and the real LLM Technical Services with
// only the network boundary stubbed — nock intercepts the fake Ollama origin, so the request
// building, response parsing, and fallback code all run for real.
describe('AccountController (e2e)', () => {
  let testApp: StartedTestApp
  let app: INestApplication
  let dataSource: DataSource

  const OWNER_ID = 'owner-1'
  const OTHER_OWNER_ID = 'owner-2'
  const PASSWORD = 'password123!'
  const tokens: Record<string, string> = {}

  // TransactionAutoCategorizer runs asynchronously (Domain Event -> Outbox -> SQS -> Consumer),
  // so its Ollama calls can land while any later test is running — a persistent interceptor
  // keeps unrelated tests from failing on those background calls. Re-applied after every
  // nock.cleanAll() (see afterEach).
  function stubAutoCategorizer(): void {
    nock(FAKE_OLLAMA_ORIGIN)
      .persist()
      .post('/api/chat', (body) => isAutoCategorizerRequest(body as OllamaChatRequestBody))
      .reply(200, (_uri, requestBody) => {
        const body = requestBody as unknown as OllamaChatRequestBody
        const category = userPrompt(body).includes('Starbucks') ? 'FOOD' : 'OTHER'
        return ollamaChatReply(JSON.stringify({ category }))
      })
  }

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
    // Importing nock patches Node's process-global http module immediately, and testcontainers
    // drives the Docker daemon over that same module — starting containers under the patch
    // breaks the runtime-strategy probing on a cold Docker state. Keep nock inactive until the
    // containers and the app are up, then activate it for the LLM stubs.
    if (nock.isActive()) nock.restore()
    testApp = await startTestApp()
    nock.activate()
    app = testApp.app
    dataSource = testApp.dataSource
    stubAutoCategorizer()

    await signUp(OWNER_ID)
    await signUp(OTHER_OWNER_ID)
    tokens[OWNER_ID] = await signIn(OWNER_ID)
    tokens[OTHER_OWNER_ID] = await signIn(OTHER_OWNER_ID)
  }, 180000)

  afterEach(() => {
    // Drops any per-test interceptor a failed test may have left behind, then restores the
    // always-on background-categorizer stub.
    nock.cleanAll()
    stubAutoCategorizer()
  })

  afterAll(async () => {
    // Unpatch nock BEFORE shutdown: app.close()/container stops talk to the Docker socket over
    // the process-global http module nock patches, and the patch also must not outlive this
    // file (Jest sandboxes user modules per file, but not core modules). Any LLM call a
    // background consumer fires during the shutdown window just DNS-fails against the fake
    // origin and takes the service's graceful fallback.
    nock.cleanAll()
    nock.restore()
    await testApp?.stop()
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

    it('when_a_row_is_persisted_then_its_createdAt_wall_clock_is_the_UTC_instant', async () => {
      const beforeMillis = Date.now()
      const { accountId } = await createAccount()
      const afterMillis = Date.now()

      // Read the column as text rather than as a Date. The column is `TIMESTAMP` (WITHOUT TIME
      // ZONE), so pg would parse a Date back using the SAME local offset it wrote with — a
      // round-trip through a Date object cancels the bug out and proves nothing. The text form
      // is the raw wall clock Postgres actually stored, with no offset attached.
      const rows = await dataSource.query<Array<{ createdAtText: string }>>(
        'SELECT "createdAt"::text AS "createdAtText" FROM "account" WHERE "accountId" = $1',
        [accountId]
      )
      expect(rows).toHaveLength(1)

      // Interpreting that wall clock AS UTC has to land back on the instant the request was
      // made. On a process running in Asia/Seoul the driver would have written a KST wall
      // clock, so this parses to 9 hours in the future and the assertion fails — which is the
      // whole point of the test. src/config/timezone.config.ts (loaded via the setupFiles entry in
      // jest.e2e.config.ts, exactly as main.ts loads it) is what keeps it passing.
      const storedAsUtcMillis = Date.parse(`${rows[0].createdAtText.replace(' ', 'T')}Z`)
      expect(storedAsUtcMillis).toBeGreaterThanOrEqual(beforeMillis - 1000)
      expect(storedAsUtcMillis).toBeLessThanOrEqual(afterMillis + 1000)
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

    it('when_filtering_by_type_then_returns_only_matching_transactions', async () => {
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
        .query({ type: 'WITHDRAWAL' })

      expect(response.status).toBe(200)
      expect(response.body.count).toBe(1)
      expect(response.body.transactions[0].type).toBe('WITHDRAWAL')
    })
  })

  // The Ollama call TransactionAutoCategorizer makes is intercepted by nock (the persistent
  // stub above replies FOOD for a Starbucks merchant), so the REAL LLM path — request building,
  // schema-constrained response parsing, category validation — runs end to end, along with the
  // real async pipeline: Domain Event → Outbox → SQS → OutboxConsumer →
  // CategorizeTransactionHandler → the repository write.
  describe('Transaction auto-categorization (merchantName -> category)', () => {
    it('withdraw_with_a_merchantName_then_the_transaction_is_asynchronously_categorized', async () => {
      const account = await createAccount()
      await request(app.getHttpServer())
        .post(`/accounts/${account.accountId}/deposit`)
        .set('Authorization', authHeader(OWNER_ID))
        .send({ amount: 10000 })
      await request(app.getHttpServer())
        .post(`/accounts/${account.accountId}/withdraw`)
        .set('Authorization', authHeader(OWNER_ID))
        .send({ amount: 5500, merchantName: 'Starbucks Gangnam' })

      let categorizedTransaction: { merchantName?: string; category?: string } | undefined
      for (let i = 0; i < 150; i++) {
        const response = await request(app.getHttpServer())
          .get(`/accounts/${account.accountId}/transactions`)
          .set('Authorization', authHeader(OWNER_ID))
          .query({ type: 'WITHDRAWAL' })
        categorizedTransaction = response.body.transactions[0]
        if (categorizedTransaction?.category) break
        await new Promise((resolve) => setTimeout(resolve, 200))
      }

      expect(categorizedTransaction?.merchantName).toBe('Starbucks Gangnam')
      expect(categorizedTransaction?.category).toBe('FOOD')
    }, 60000)

    it('withdraw_without_a_merchantName_then_the_transaction_is_never_categorized', async () => {
      const account = await createAccount()
      await request(app.getHttpServer())
        .post(`/accounts/${account.accountId}/deposit`)
        .set('Authorization', authHeader(OWNER_ID))
        .send({ amount: 10000 })
      await request(app.getHttpServer())
        .post(`/accounts/${account.accountId}/withdraw`)
        .set('Authorization', authHeader(OWNER_ID))
        .send({ amount: 5500 })

      // No merchantName to react to, so there's nothing to wait out — give the (skipped)
      // reaction the same window as the happy path would need, then assert it never ran.
      await new Promise((resolve) => setTimeout(resolve, 5000))
      const response = await request(app.getHttpServer())
        .get(`/accounts/${account.accountId}/transactions`)
        .set('Authorization', authHeader(OWNER_ID))
        .query({ type: 'WITHDRAWAL' })

      expect(response.body.transactions[0].merchantName).toBeUndefined()
      expect(response.body.transactions[0].category).toBeUndefined()
    }, 60000)
  })

  describe('Withdrawal anomaly alert (DetectWithdrawalAnomalyHandler)', () => {
    async function withdraw(accountId: string, amount: number): Promise<void> {
      await request(app.getHttpServer())
        .post(`/accounts/${accountId}/withdraw`)
        .set('Authorization', authHeader(OWNER_ID))
        .send({ amount })
    }

    it('a_withdrawal_far_outside_the_accounts_normal_range_then_an_alert_email_is_sent', async () => {
      const account = await createAccount()
      await request(app.getHttpServer())
        .post(`/accounts/${account.accountId}/deposit`)
        .set('Authorization', authHeader(OWNER_ID))
        .send({ amount: 10_000_000 })

      // Builds a normal history of small, similar withdrawals — AnomalyDetectionService needs
      // at least 5 to compute a meaningful baseline.
      for (const amount of [10000, 12000, 9000, 11000, 10500]) {
        await withdraw(account.accountId, amount)
      }
      // Far beyond that history's spread — a genuine statistical outlier.
      await withdraw(account.accountId, 5_000_000)

      const sentEmailRepo = dataSource.getRepository(SentEmailEntity)
      let alertEmail: SentEmailEntity | null = null
      for (let i = 0; i < 150; i++) {
        alertEmail = await sentEmailRepo.findOneBy({ accountId: account.accountId, eventType: 'WithdrawalAnomalyDetected' })
        if (alertEmail) break
        await new Promise((resolve) => setTimeout(resolve, 200))
      }

      expect(alertEmail).not.toBeNull()
      expect(alertEmail?.recipient).toBe('owner1@example.com')
    }, 60000)

    it('withdrawals_that_stay_within_the_accounts_normal_range_then_no_alert_email_is_ever_sent', async () => {
      const account = await createAccount()
      await request(app.getHttpServer())
        .post(`/accounts/${account.accountId}/deposit`)
        .set('Authorization', authHeader(OWNER_ID))
        .send({ amount: 10_000_000 })

      for (const amount of [10000, 12000, 9000, 11000, 10500, 10800]) {
        await withdraw(account.accountId, amount)
      }

      // No single "the async work finished" signal to await for a negative case — give the
      // pipeline the same window the positive test needs, then assert nothing landed.
      await new Promise((resolve) => setTimeout(resolve, 5000))
      const sentEmailRepo = dataSource.getRepository(SentEmailEntity)
      const alertEmail = await sentEmailRepo.findOneBy({ accountId: account.accountId, eventType: 'WithdrawalAnomalyDetected' })

      expect(alertEmail).toBeNull()
    }, 60000)
  })

  // The two LLM Technical Services behind this endpoint (NlTransactionQueryTranslator +
  // NlTransactionAnswerComposer, see account/infrastructure) run for REAL here — nock stubs
  // Ollama's /api/chat at the network boundary, so the request shape they build, the
  // schema-constrained parse, and the grounded answer path are all exercised and asserted.
  describe('POST /accounts/:accountId/transactions/ask', () => {
    it('when_asked_a_question_then_returns_200_with_an_answer_grounded_in_the_requesters_own_transactions', async () => {
      const account = await createAccount()
      await request(app.getHttpServer())
        .post(`/accounts/${account.accountId}/deposit`)
        .set('Authorization', authHeader(OWNER_ID))
        .send({ amount: 10000 })

      const QUESTION = 'How much have I deposited?'
      const ANSWER = 'You have deposited 10000 KRW in total.'
      let translatorRequest: OllamaChatRequestBody | undefined
      let composerRequest: OllamaChatRequestBody | undefined

      nock(FAKE_OLLAMA_ORIGIN)
        .post('/api/chat', (body) => isQueryTranslatorRequest(body as OllamaChatRequestBody))
        .reply(200, (_uri, requestBody) => {
          translatorRequest = requestBody as unknown as OllamaChatRequestBody
          return ollamaChatReply(JSON.stringify({ type: 'DEPOSIT', fromDate: '', toDate: '' }))
        })
      nock(FAKE_OLLAMA_ORIGIN)
        .post('/api/chat', (body) => isAnswerComposerRequest(body as OllamaChatRequestBody))
        .reply(200, (_uri, requestBody) => {
          composerRequest = requestBody as unknown as OllamaChatRequestBody
          return ollamaChatReply(ANSWER)
        })

      const response = await request(app.getHttpServer())
        .post(`/accounts/${account.accountId}/transactions/ask`)
        .set('Authorization', authHeader(OWNER_ID))
        .send({ question: QUESTION })

      expect(response.status).toBe(200)
      // The stubbed LLM answer came back verbatim — the real parse path ran, not the fallback.
      expect(response.body.answer).toBe(ANSWER)
      expect(response.body.matchedCount).toBe(1)

      // The translator got the raw question plus a JSON-schema-constrained format field.
      expect(translatorRequest).toBeDefined()
      expect(userPrompt(translatorRequest!)).toBe(QUESTION)
      expect(translatorRequest!.format).toBeDefined()
      expect(translatorRequest!.stream).toBe(false)
      // The composer prompt was grounded in the retrieved transaction, not free-floating.
      expect(composerRequest).toBeDefined()
      expect(userPrompt(composerRequest!)).toContain(QUESTION)
      expect(userPrompt(composerRequest!)).toContain('DEPOSIT 10000 KRW')
    })

    it('when_the_llm_is_unavailable_then_still_returns_200_with_a_plain_fallback_answer', async () => {
      const account = await createAccount()
      await request(app.getHttpServer())
        .post(`/accounts/${account.accountId}/deposit`)
        .set('Authorization', authHeader(OWNER_ID))
        .send({ amount: 10000 })

      // Both LLM calls fail with a 500 — the endpoint must still answer from the retrieved
      // data (empty filter + templated summary), never surface the infrastructure failure.
      nock(FAKE_OLLAMA_ORIGIN)
        .post('/api/chat', (body) => {
          const chatBody = body as OllamaChatRequestBody
          return isQueryTranslatorRequest(chatBody) || isAnswerComposerRequest(chatBody)
        })
        .times(2)
        .reply(500)

      const response = await request(app.getHttpServer())
        .post(`/accounts/${account.accountId}/transactions/ask`)
        .set('Authorization', authHeader(OWNER_ID))
        .send({ question: 'How much have I deposited?' })

      expect(response.status).toBe(200)
      expect(response.body.answer).toContain('Found 1 matching transaction')
      expect(response.body.matchedCount).toBe(1)
    })

    it('when_the_question_is_empty_then_returns_400', async () => {
      const account = await createAccount()

      const response = await request(app.getHttpServer())
        .post(`/accounts/${account.accountId}/transactions/ask`)
        .set('Authorization', authHeader(OWNER_ID))
        .send({ question: '' })

      expect(response.status).toBe(400)
    })

    it('when_the_account_does_not_exist_then_returns_404', async () => {
      const response = await request(app.getHttpServer())
        .post('/accounts/non-existent/transactions/ask')
        .set('Authorization', authHeader(OWNER_ID))
        .send({ question: 'anything' })

      expect(response.status).toBe(404)
    })

    it('when_a_different_owner_asks_about_this_account_then_returns_404', async () => {
      const account = await createAccount()

      const response = await request(app.getHttpServer())
        .post(`/accounts/${account.accountId}/transactions/ask`)
        .set('Authorization', authHeader(OTHER_OWNER_ID))
        .send({ question: 'anything' })

      expect(response.status).toBe(404)
    })
  })
})

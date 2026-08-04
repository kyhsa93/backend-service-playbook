package com.example.accountservice.taskqueue

import com.example.accountservice.AccountServiceApplication
import com.example.accountservice.account.infrastructure.persistence.SpendingAnalysisJpaEntity
import com.example.accountservice.account.infrastructure.persistence.SpendingAnalysisJpaRepository
import com.example.accountservice.account.infrastructure.persistence.SpendingForecastJpaRepository
import com.example.accountservice.account.infrastructure.persistence.TransactionJpaRepository
import com.example.accountservice.account.infrastructure.scheduling.InterestPaymentScheduler
import com.example.accountservice.account.infrastructure.scheduling.SpendingAnalysisScheduler
import com.example.accountservice.account.infrastructure.scheduling.SpendingForecastScheduler
import com.example.accountservice.account.infrastructure.scheduling.computePreviousSpendingAnalysisPeriod
import com.example.accountservice.account.infrastructure.scheduling.computeSpendingForecastMonth
import com.example.accountservice.card.infrastructure.scheduling.CardStatementScheduler
import com.example.accountservice.common.generateId
import com.example.accountservice.notification.infrastructure.persistence.SentEmailJpaRepository
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.localstack.LocalStackContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ses.SesClient
import software.amazon.awssdk.services.ses.model.VerifyEmailIdentityRequest
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest
import software.amazon.awssdk.services.sqs.model.QueueAttributeName
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * An e2e test that actually exercises the full path prescribed by scheduling.md (Scheduler →
 * task_outbox → TaskOutboxPoller → SQS FIFO → TaskQueueConsumer → TaskHandlerRegistry →
 * TaskController → CommandService). Instead of waiting for a real Cron tick (up to a day/month),
 * it directly calls the Scheduler's enqueue method — verifying the Task Queue path the same way
 * [com.example.accountservice.account.notification.NotificationE2ETest] verifies the Outbox path.
 */
@Testcontainers
@AutoConfigureTestRestTemplate
@SpringBootTest(
    classes = [AccountServiceApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class TaskQueueE2ETest {
    companion object {
        private const val SENDER_EMAIL = "no-reply@backend-service-playbook.example.com"
        private const val TEST_PASSWORD = "password123!"

        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")

        @Container
        @JvmStatic
        val localstack: LocalStackContainer =
            LocalStackContainer(DockerImageName.parse("localstack/localstack:3.0"))
                .withServices(LocalStackContainer.Service.SES, LocalStackContainer.Service.SQS)

        @DynamicPropertySource
        @JvmStatic
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.jpa.hibernate.ddl-auto") { "create-drop" }
            registry.add("spring.flyway.enabled") { "false" }
            registry.add("AWS_REGION") { localstack.region }
            registry.add("AWS_ACCESS_KEY_ID") { localstack.accessKey }
            registry.add("AWS_SECRET_ACCESS_KEY") { localstack.secretKey }
            registry.add("AWS_ENDPOINT_URL") { localstack.getEndpointOverride(LocalStackContainer.Service.SES).toString() }
            registry.add("SQS_DOMAIN_EVENT_QUEUE_URL") { createQueue("domain-events", fifo = false) }
            registry.add("SQS_TASK_QUEUE_URL") { createQueue("task-queue.fifo", fifo = true) }
            // The test calls the write API more times in a short span than the default
            // limit-for-period (10), so for tests only we loosen it generously so we're verifying
            // the Task Queue path rather than rate limiting itself.
            registry.add("resilience4j.ratelimiter.instances.http-write.limit-for-period") { "1000" }
        }

        // Since the container is already up (a static @Container field), we create the queue
        // directly and return its URL before the Spring context binds SqsProperties — DLQ/
        // RedrivePolicy are omitted for testing purposes (retry observation isn't needed).
        private fun createQueue(
            name: String,
            fifo: Boolean,
        ): String {
            val sqsClient =
                SqsClient
                    .builder()
                    .region(Region.of(localstack.region))
                    .credentialsProvider(
                        StaticCredentialsProvider.create(AwsBasicCredentials.create(localstack.accessKey, localstack.secretKey)),
                    ).endpointOverride(localstack.getEndpointOverride(LocalStackContainer.Service.SQS))
                    .build()
            val attributes = if (fifo) mapOf(QueueAttributeName.FIFO_QUEUE to "true") else emptyMap()
            val queueUrl =
                sqsClient
                    .createQueue(
                        CreateQueueRequest
                            .builder()
                            .queueName(name)
                            .attributes(attributes)
                            .build(),
                    ).queueUrl()
            sqsClient.close()
            return queueUrl
        }

        @BeforeAll
        @JvmStatic
        fun verifySesSender() {
            val sesClient =
                SesClient
                    .builder()
                    .region(Region.of(localstack.region))
                    .credentialsProvider(
                        StaticCredentialsProvider.create(AwsBasicCredentials.create(localstack.accessKey, localstack.secretKey)),
                    ).endpointOverride(localstack.getEndpointOverride(LocalStackContainer.Service.SES))
                    .build()
            sesClient.verifyEmailIdentity(VerifyEmailIdentityRequest.builder().emailAddress(SENDER_EMAIL).build())
            sesClient.close()
        }
    }

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var interestPaymentScheduler: InterestPaymentScheduler

    @Autowired
    private lateinit var cardStatementScheduler: CardStatementScheduler

    @Autowired
    private lateinit var spendingAnalysisScheduler: SpendingAnalysisScheduler

    @Autowired
    private lateinit var spendingForecastScheduler: SpendingForecastScheduler

    @Autowired
    private lateinit var sentEmailJpaRepository: SentEmailJpaRepository

    @Autowired
    private lateinit var transactionJpaRepository: TransactionJpaRepository

    @Autowired
    private lateinit var spendingAnalysisJpaRepository: SpendingAnalysisJpaRepository

    @Autowired
    private lateinit var spendingForecastJpaRepository: SpendingForecastJpaRepository

    private val tokenCache = mutableMapOf<String, String>()

    private fun tokenFor(userId: String): String =
        tokenCache.getOrPut(userId) {
            restTemplate.postForEntity("/auth/sign-up", mapOf("userId" to userId, "password" to TEST_PASSWORD), Map::class.java)
            val response =
                restTemplate.postForEntity(
                    "/auth/sign-in",
                    mapOf("userId" to userId, "password" to TEST_PASSWORD),
                    Map::class.java,
                )
            response.body!!["accessToken"] as String
        }

    private fun headersFor(ownerId: String): HttpHeaders {
        val headers = HttpHeaders()
        headers.setBearerAuth(tokenFor(ownerId))
        headers.contentType = MediaType.APPLICATION_JSON
        return headers
    }

    private fun post(
        path: String,
        ownerId: String,
        body: Map<String, Any>,
    ): ResponseEntity<Map<*, *>> = restTemplate.exchange(path, HttpMethod.POST, HttpEntity(body, headersFor(ownerId)), Map::class.java)

    private fun get(
        path: String,
        ownerId: String,
    ): ResponseEntity<Map<*, *>> = restTemplate.exchange(path, HttpMethod.GET, HttpEntity<Void>(headersFor(ownerId)), Map::class.java)

    private fun createAccount(
        ownerId: String,
        email: String,
    ): String {
        val response = post("/accounts", ownerId, mapOf("currency" to "KRW", "email" to email))
        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        return response.body!!["accountId"] as String
    }

    private fun withdraw(
        accountId: String,
        ownerId: String,
        amount: Int,
    ): String {
        val response = post("/accounts/$accountId/withdraw", ownerId, mapOf("amount" to amount))
        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        return response.body!!["transactionId"] as String
    }

    // Backdates a transaction's createdAt directly at the persistence layer — there is no legitimate
    // domain use case for changing it after the fact, so the test reaches around the API the same way
    // the card-statement test backdates payments.
    private fun backdateTransaction(
        transactionId: String,
        createdAt: LocalDateTime,
    ) {
        val entity =
            transactionJpaRepository.findByTransactionId(transactionId) ?: throw AssertionError("transaction not found: $transactionId")
        entity.createdAt = createdAt
        transactionJpaRepository.save(entity)
    }

    @Test
    fun `enqueueing the recurring interest-payment task applies interest, and re-running it same-day does not pay twice`() {
        val ownerId = "interest-owner-1"
        val email = "interest-owner-1@example.com"
        val accountId = createAccount(ownerId, email)
        // Deposit a balance large enough that a 0.01% interest rate produces an integer amount: 10,000,000 * 0.0001 = 1,000
        post("/accounts/$accountId/deposit", ownerId, mapOf("amount" to 10_000_000))

        interestPaymentScheduler.enqueueDailyInterestPayment()

        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(300)).untilAsserted {
            val account = get("/accounts/$accountId", ownerId).body!!

            @Suppress("UNCHECKED_CAST")
            val balance = account["balance"] as Map<String, Any>
            assertThat((balance["amount"] as Number).toLong()).isEqualTo(10_001_000L)
        }

        val transactions = get("/accounts/$accountId/transactions", ownerId).body!!

        @Suppress("UNCHECKED_CAST")
        val list = transactions["transactions"] as List<Map<String, Any>>
        assertThat(list.count { it["type"] == "INTEREST" }).isEqualTo(1)

        // Even when enqueued again on the same date (mimicking at-least-once redelivery), the
        // task_outbox deduplicationId UNIQUE constraint prevents duplicate insertion, and even if it
        // were processed, Account.payInterest() is idempotent so interest isn't paid twice.
        interestPaymentScheduler.enqueueDailyInterestPayment()

        Thread.sleep(3000)
        val transactionsAfterRetry = get("/accounts/$accountId/transactions", ownerId).body!!

        @Suppress("UNCHECKED_CAST")
        val listAfterRetry = transactionsAfterRetry["transactions"] as List<Map<String, Any>>
        assertThat(listAfterRetry.count { it["type"] == "INTEREST" }).isEqualTo(1)

        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(300)).untilAsserted {
            val interestEmail =
                sentEmailJpaRepository.findByAccountId(accountId).firstOrNull { it.eventType == "InterestPaid" }
                    ?: throw AssertionError("The InterestPaid send record was not saved: accountId=$accountId")
            assertThat(interestEmail.recipient).isEqualTo(email)
        }
    }

    @Test
    fun `enqueueing the monthly card-statement task sends the summary, and re-running it same-month does not send it twice`() {
        val ownerId = "statement-owner-1"
        val email = "statement-owner-1@example.com"
        val accountId = createAccount(ownerId, email)
        post("/accounts/$accountId/deposit", ownerId, mapOf("amount" to 500_000))

        val cardResponse = post("/cards", ownerId, mapOf("accountId" to accountId, "brand" to "VISA"))
        assertThat(cardResponse.statusCode).isEqualTo(HttpStatus.CREATED)
        val cardId = cardResponse.body!!["cardId"] as String

        post("/payments", ownerId, mapOf("cardId" to cardId, "amount" to 30_000))
        post("/payments", ownerId, mapOf("cardId" to cardId, "amount" to 20_000))

        cardStatementScheduler.enqueueMonthlyCardStatement()

        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(300)).untilAsserted {
            val statementEmail =
                sentEmailJpaRepository.findByAccountId(accountId).firstOrNull { it.eventType == "CardStatement" }
                    ?: throw AssertionError("The CardStatement send record was not saved: accountId=$accountId")
            assertThat(statementEmail.recipient).isEqualTo(email)
        }

        // Even when enqueued again in the same month, Card.lastStatementSentMonth (Level 1) +
        // task_outbox deduplicationId (multi-instance safety) prevent duplicate sending.
        cardStatementScheduler.enqueueMonthlyCardStatement()

        Thread.sleep(3000)
        val statementEmailsAfterRetry = sentEmailJpaRepository.findByAccountId(accountId).filter { it.eventType == "CardStatement" }
        assertThat(statementEmailsAfterRetry).hasSize(1)
    }

    @Test
    fun `an account with last months withdrawals gets an analysis row via the API, and re-enqueueing same-month does not duplicate it`() {
        val ownerId = "spending-owner-1"
        val email = "spending-owner-1@example.com"
        val accountId = createAccount(ownerId, email)
        post("/accounts/$accountId/deposit", ownerId, mapOf("amount" to 1_000_000))

        // Backdates the withdrawals into "last month" the same way the card-statement test backdates
        // payments — reusing the scheduler's own period computation so the analysis only lines up if it
        // matches the logic the Scheduler actually runs with.
        val period = computePreviousSpendingAnalysisPeriod(LocalDateTime.now(ZoneOffset.UTC))
        val backdatedAt = period.monthStart.plusDays(1)
        val transactionId1 = withdraw(accountId, ownerId, 30_000)
        val transactionId2 = withdraw(accountId, ownerId, 20_000)
        backdateTransaction(transactionId1, backdatedAt)
        backdateTransaction(transactionId2, backdatedAt)

        spendingAnalysisScheduler.enqueueMonthlySpendingAnalysis()

        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(300)).untilAsserted {
            val analysis = spendingAnalysisJpaRepository.findByAccountIdAndAnalysisMonth(accountId, period.analysisMonth)
            assertThat(analysis).isNotNull
            assertThat(analysis!!.totalAmount).isEqualTo(50000L)
            assertThat(analysis.transactionCount).isEqualTo(2L)
            assertThat(analysis.averageAmount).isEqualTo(25000L)
            // No prior-prior month withdrawal history exists, so the comparison baseline is 0 — the
            // %-change is capped at 100 and the trend is INCREASING (see SpendingAnalysis.create).
            assertThat(analysis.changeFromPreviousMonth).isEqualTo(100L)
            assertThat(analysis.trend).isEqualTo("INCREASING")
        }

        val response = get("/accounts/$accountId/spending-analysis?month=${period.analysisMonth}-01", ownerId)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat((response.body!!["totalAmount"] as Number).toLong()).isEqualTo(50000L)
        assertThat(response.body!!["trend"]).isEqualTo("INCREASING")

        // Since it's the same month's dedupId, even if the second enqueue is reprocessed, the
        // (accountId, analysisMonth) unique index + the hasAnalysis precheck must prevent a duplicate row.
        spendingAnalysisScheduler.enqueueMonthlySpendingAnalysis()
        Thread.sleep(3000)
        assertThat(spendingAnalysisJpaRepository.countByAccountIdAndAnalysisMonth(accountId, period.analysisMonth)).isEqualTo(1L)
    }

    @Test
    fun `when no analysis has been computed for the requested month then returns 404`() {
        val ownerId = "spending-owner-2"
        val email = "spending-owner-2@example.com"
        val accountId = createAccount(ownerId, email)

        val response = get("/accounts/$accountId/spending-analysis?month=2020-01-01", ownerId)

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(response.body!!["code"]).isEqualTo("SPENDING_ANALYSIS_NOT_FOUND")
    }

    @Test
    fun `an account with 3 months of spending-analysis history gets a trained forecast, and re-enqueueing same-month is idempotent`() {
        val ownerId = "forecast-owner-1"
        val email = "forecast-owner-1@example.com"
        val accountId = createAccount(ownerId, email)

        // Seeds 3 months of spending_analysis history directly — this test's concern is
        // ForecastSpendingService training on existing history, re-deriving that history is
        // separately covered by the spending-analysis tests above.
        val forecastMonth = computeSpendingForecastMonth(LocalDateTime.now(ZoneOffset.UTC))
        val amounts = listOf(10000L, 20000L, 30000L)
        for (monthsAgo in 3 downTo 1) {
            val now = LocalDateTime.now(ZoneOffset.UTC)
            val monthDate = now.toLocalDate().withDayOfMonth(1).minusMonths(monthsAgo.toLong())
            val analysisMonth = "%04d-%02d".format(monthDate.year, monthDate.monthValue)
            spendingAnalysisJpaRepository.save(
                SpendingAnalysisJpaEntity(
                    id = null,
                    analysisId = generateId(),
                    accountId = accountId,
                    analysisMonth = analysisMonth,
                    totalAmount = amounts[3 - monthsAgo],
                    transactionCount = 1,
                    averageAmount = amounts[3 - monthsAgo],
                    changeFromPreviousMonth = 0,
                    trend = "STABLE",
                    createdAt = LocalDateTime.now(),
                ),
            )
        }

        spendingForecastScheduler.enqueueMonthlySpendingForecast()

        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(300)).untilAsserted {
            val forecast = spendingForecastJpaRepository.findByAccountIdAndForecastMonth(accountId, forecastMonth)
            assertThat(forecast).isNotNull
            // A perfectly linear history (10000, 20000, 30000) extrapolates exactly to 40000 with
            // full confidence — see SpendingForecastModelImplTest.
            assertThat(forecast!!.predictedAmount).isEqualTo(40000L)
            assertThat(forecast.confidence).isEqualTo("HIGH")
            assertThat(forecast.historyMonthsUsed).isEqualTo(3)
        }

        val response = get("/accounts/$accountId/spending-forecast?month=$forecastMonth-01", ownerId)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat((response.body!!["predictedAmount"] as Number).toLong()).isEqualTo(40000L)
        assertThat(response.body!!["confidence"]).isEqualTo("HIGH")

        // Since it's the same month's dedupId, even if the second enqueue is reprocessed, the
        // (accountId, forecastMonth) unique constraint + the hasForecast precheck must prevent a
        // duplicate row.
        spendingForecastScheduler.enqueueMonthlySpendingForecast()
        Thread.sleep(3000)
        assertThat(spendingForecastJpaRepository.countByAccountIdAndForecastMonth(accountId, forecastMonth)).isEqualTo(1L)
    }

    @Test
    fun `an account younger than 3 months of spending analysis history gets no forecast and the API returns 404`() {
        val ownerId = "forecast-owner-2"
        val email = "forecast-owner-2@example.com"
        val accountId = createAccount(ownerId, email)

        spendingForecastScheduler.enqueueMonthlySpendingForecast()
        Thread.sleep(5000)

        val forecastMonth = computeSpendingForecastMonth(LocalDateTime.now(ZoneOffset.UTC))
        val response = get("/accounts/$accountId/spending-forecast?month=$forecastMonth-01", ownerId)

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(response.body!!["code"]).isEqualTo("SPENDING_FORECAST_NOT_FOUND")
    }
}

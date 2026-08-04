package com.example.accountservice.account.application.event

import com.example.accountservice.AccountServiceApplication
import com.example.accountservice.notification.infrastructure.persistence.SentEmailJpaRepository
import com.example.accountservice.support.FakeOllamaServer
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterAll
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

/**
 * Exercises the real async pipeline end to end: withdraw -> MoneyWithdrawnEvent -> Outbox -> SQS ->
 * OutboxConsumer -> EventHandlerRegistry -> DetectWithdrawalAnomalyEventHandler -> a real SES send
 * (via LocalStack), verified through the persisted SentEmail row — the same pattern
 * [com.example.accountservice.notification.NotificationE2ETest] uses. With this handler added, the
 * same MoneyWithdrawnEvent delivery now fans out to 3 subscribers (MoneyWithdrawnEventHandler,
 * CategorizeTransactionEventHandler, and this one), all registered on EventHandlerRegistry's 1:N
 * contract (see its class doc).
 */
@Testcontainers
@AutoConfigureTestRestTemplate
@SpringBootTest(
    classes = [AccountServiceApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class WithdrawalAnomalyAlertE2ETest {
    companion object {
        private const val SENDER_EMAIL = "no-reply@backend-service-playbook.example.com"
        private const val OWNER_ID = "anomaly-owner-1"
        private const val RECIPIENT_EMAIL = "anomaly-owner-1@example.com"
        private const val TEST_PASSWORD = "password123!"

        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")

        @Container
        @JvmStatic
        val localstack: LocalStackContainer =
            LocalStackContainer(DockerImageName.parse("localstack/localstack:3.0"))
                .withServices(LocalStackContainer.Service.SES, LocalStackContainer.Service.SQS)

        // In-process fake Ollama (see FakeOllamaServer) — llm.ollama-base-url points at it below,
        // so CategorizeTransactionEventHandler (the 2nd MoneyWithdrawnEvent subscriber this test
        // asserts on) categorizes through the real request/parse path deterministically.
        @JvmStatic
        val fakeOllama = FakeOllamaServer()

        @AfterAll
        @JvmStatic
        fun stopFakeOllama() {
            fakeOllama.stop()
        }

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
            registry.add("SQS_DOMAIN_EVENT_QUEUE_URL") { createDomainEventQueue() }
            registry.add("SQS_TASK_QUEUE_URL") { createTaskQueue() }
            registry.add("llm.ollama-base-url") { fakeOllama.baseUrl }
            // The positive-case test performs 6 withdrawals in a short span, well past the
            // default limit-for-period (10 would be fine, but the negative test adds 6 more) —
            // loosen it generously so we're verifying the anomaly-alert logic rather than rate
            // limiting itself (the same reasoning as NotificationE2ETest).
            registry.add("resilience4j.ratelimiter.instances.http-write.limit-for-period") { "1000" }
        }

        private fun createDomainEventQueue(): String {
            val sqsClient =
                SqsClient
                    .builder()
                    .region(Region.of(localstack.region))
                    .credentialsProvider(
                        StaticCredentialsProvider.create(AwsBasicCredentials.create(localstack.accessKey, localstack.secretKey)),
                    ).endpointOverride(localstack.getEndpointOverride(LocalStackContainer.Service.SQS))
                    .build()
            val queueUrl = sqsClient.createQueue(CreateQueueRequest.builder().queueName("domain-events").build()).queueUrl()
            sqsClient.close()
            return queueUrl
        }

        private fun createTaskQueue(): String {
            val sqsClient =
                SqsClient
                    .builder()
                    .region(Region.of(localstack.region))
                    .credentialsProvider(
                        StaticCredentialsProvider.create(AwsBasicCredentials.create(localstack.accessKey, localstack.secretKey)),
                    ).endpointOverride(localstack.getEndpointOverride(LocalStackContainer.Service.SQS))
                    .build()
            val queueUrl =
                sqsClient
                    .createQueue(
                        CreateQueueRequest
                            .builder()
                            .queueName("task-queue.fifo")
                            .attributes(mapOf(QueueAttributeName.FIFO_QUEUE to "true"))
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
    private lateinit var sentEmailJpaRepository: SentEmailJpaRepository

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

    private fun createAccountAndDeposit(depositAmount: Long): String {
        val createResponse = post("/accounts", OWNER_ID, mapOf("currency" to "KRW", "email" to RECIPIENT_EMAIL))
        assertThat(createResponse.statusCode).isEqualTo(HttpStatus.CREATED)
        val accountId = createResponse.body!!["accountId"] as String

        val depositResponse = post("/accounts/$accountId/deposit", OWNER_ID, mapOf("amount" to depositAmount))
        assertThat(depositResponse.statusCode).isEqualTo(HttpStatus.CREATED)
        return accountId
    }

    private fun withdraw(
        accountId: String,
        amount: Long,
        merchantName: String? = null,
    ) {
        val body = if (merchantName != null) mapOf("amount" to amount, "merchantName" to merchantName) else mapOf("amount" to amount)
        val response = post("/accounts/$accountId/withdraw", OWNER_ID, body)
        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
    }

    @Suppress("UNCHECKED_CAST")
    private fun firstWithdrawalTransaction(accountId: String): Map<String, Any>? {
        val response =
            restTemplate.exchange(
                "/accounts/$accountId/transactions?type=WITHDRAWAL",
                HttpMethod.GET,
                HttpEntity<Void>(headersFor(OWNER_ID)),
                Map::class.java,
            )
        val transactions = response.body!!["transactions"] as List<Map<String, Any>>
        return transactions.firstOrNull()
    }

    @Test
    fun `a withdrawal far outside the account's normal range then an alert email is sent`() {
        val accountId = createAccountAndDeposit(10_000_000)

        // Builds a normal history of small, similar withdrawals — AnomalyDetectionService needs
        // at least 5 to compute a meaningful baseline.
        for (amount in listOf(10000L, 12000L, 9000L, 11000L, 10500L)) {
            withdraw(accountId, amount)
        }
        // Far beyond that history's spread — a genuine statistical outlier. Also carries a
        // merchantName so CategorizeTransactionEventHandler has something to react to, proving
        // all 3 MoneyWithdrawnEvent subscribers (MoneyWithdrawnEventHandler,
        // CategorizeTransactionEventHandler, DetectWithdrawalAnomalyEventHandler) actually run
        // for the same delivery — the EventHandlerRegistry 1:N contract this feature specifically
        // exercises with a 3rd handler.
        withdraw(accountId, 5_000_000L, "Suspicious Wire Transfer")

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300)).untilAsserted {
            // Handler 3: DetectWithdrawalAnomalyEventHandler sent the alert.
            val alertEmail =
                sentEmailJpaRepository
                    .findByAccountId(accountId)
                    .firstOrNull { it.eventType == "WithdrawalAnomalyDetected" }
            assertThat(alertEmail).isNotNull()
            assertThat(alertEmail!!.recipient).isEqualTo(RECIPIENT_EMAIL)

            // Handler 1: MoneyWithdrawnEventHandler sent a completion email for every withdrawal
            // (6 in this test, one per withdraw() call) — findByAccountId + firstOrNull rather
            // than a single-result finder, since there are multiple MoneyWithdrawn rows for this
            // account.
            val completionEmailSent =
                sentEmailJpaRepository
                    .findByAccountId(accountId)
                    .any { it.eventType == "MoneyWithdrawn" }
            assertThat(completionEmailSent).isTrue()

            // Handler 2: CategorizeTransactionEventHandler categorized the transaction — the fake
            // Ollama deterministically answers OTHER for a merchant it doesn't recognize (only a
            // Starbucks merchant maps to FOOD), through the real request/parse path.
            val transaction = firstWithdrawalTransaction(accountId)
            assertThat(transaction).isNotNull()
            assertThat(transaction!!["category"]).isEqualTo("OTHER")
        }
    }

    @Test
    fun `withdrawals that stay within the account's normal range then no alert email is ever sent`() {
        val accountId = createAccountAndDeposit(10_000_000)

        for (amount in listOf(10000L, 12000L, 9000L, 11000L, 10500L, 10800L)) {
            withdraw(accountId, amount)
        }

        // No single "the async work finished" signal to await for a negative case — give the
        // pipeline the same window the positive test needs, then assert nothing landed.
        Thread.sleep(5000)
        val alertEmail =
            sentEmailJpaRepository
                .findByAccountId(accountId)
                .firstOrNull { it.eventType == "WithdrawalAnomalyDetected" }

        assertThat(alertEmail).isNull()
    }
}

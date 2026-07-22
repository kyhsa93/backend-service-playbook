package com.example.accountservice.taskqueue

import com.example.accountservice.AccountServiceApplication
import com.example.accountservice.account.infrastructure.scheduling.InterestPaymentScheduler
import com.example.accountservice.card.infrastructure.scheduling.CardStatementScheduler
import com.example.accountservice.notification.infrastructure.persistence.SentEmailJpaRepository
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
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
 * An e2e test that actually exercises the full path prescribed by scheduling.md (Scheduler →
 * task_outbox → TaskOutboxPoller → SQS FIFO → TaskQueueConsumer → TaskHandlerRegistry →
 * TaskController → CommandService). Instead of waiting for a real Cron tick (up to a day/month),
 * it directly calls the Scheduler's enqueue method — verifying the Task Queue path the same way
 * [com.example.accountservice.account.notification.NotificationE2ETest] verifies the Outbox path.
 */
@Testcontainers
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
}

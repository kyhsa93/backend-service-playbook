package com.example.accountservice.notification

import com.example.accountservice.AccountServiceApplication
import com.example.accountservice.notification.infrastructure.persistence.SentEmailJpaRepository
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
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
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Verifies that each Account-domain command (open/deposit/withdraw/suspend/reactivate/close) sends
 * a notification email via SES through the Outbox path.
 *
 * [com.example.accountservice.account.interfaces.rest.AccountControllerE2ETest] already covers the
 * account-opening/deposit notifications, but this class is dedicated to verifying notification
 * sending across all 6 commands — it also serves as a regression test for the `outbox/` mechanism
 * (the asynchronous path where OutboxWriter commits the event in the same transaction as the
 * Aggregate save, OutboxPoller independently publishes it to SQS, and OutboxConsumer receives it and
 * calls the corresponding `*EventHandler` in `application/event/`) — since a notification is sent up
 * to a few seconds after the HTTP response comes back, we poll with [awaitEmailSent].
 */
@Testcontainers
@SpringBootTest(
    classes = [AccountServiceApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class NotificationE2ETest {
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
            registry.add("SQS_DOMAIN_EVENT_QUEUE_URL") { createDomainEventQueue() }
            registry.add("SQS_TASK_QUEUE_URL") { createTaskQueue() }
            // The test calls the write API more times in a short span than the default
            // limit-for-period (10), so for tests only we loosen it generously so we're verifying
            // the notification-sending logic rather than rate limiting itself.
            registry.add("resilience4j.ratelimiter.instances.http-write.limit-for-period") { "1000" }
        }

        // Since the container is already up (a static @Container field), we create the queue
        // directly and return its URL before the Spring context binds SqsProperties — DLQ/
        // RedrivePolicy are omitted here (retry observation isn't needed for testing purposes).
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

        // Since SqsProperties.taskQueueUrl is also @NotBlank (config.md fail-fast), this test — which
        // doesn't actually use the Task Queue path — also creates a FIFO queue just to boot the
        // context (the same approach as TaskQueueE2ETest).
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
            // LocalStack's SES emulator enforces sender-identity verification just like real SES,
            // so we verify the sender address up front before the test sends any email.
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

    private fun createAccount(
        ownerId: String,
        email: String,
        currency: String = "KRW",
    ): String {
        val response = post("/accounts", ownerId, mapOf("currency" to currency, "email" to email))
        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        return response.body!!["accountId"] as String
    }

    private fun fetchSesMessages(): List<Map<String, Any>> {
        val endpoint = localstack.getEndpointOverride(LocalStackContainer.Service.SES)
        val client = HttpClient.newHttpClient()
        val request = HttpRequest.newBuilder(URI.create("$endpoint/_aws/ses")).GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        val mapper = jacksonObjectMapper()

        @Suppress("UNCHECKED_CAST")
        val root = mapper.readValue(response.body(), Map::class.java) as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        return root["messages"] as? List<Map<String, Any>> ?: emptyList()
    }

    /**
     * Verifies both the send record saved in the DB and the message that actually arrived at
     * LocalStack SES.
     *
     * Processing goes through the Outbox → SQS (OutboxPoller, at most a 1-second cycle) →
     * OutboxConsumer → EventHandler path asynchronously, so the email may not have been sent yet by
     * the time the HTTP response comes back — poll with a timeout more generous than the
     * LocalStack+SQS latency (2-4 seconds) actually measured in this repository.
     */
    private fun awaitEmailSent(
        accountId: String,
        eventType: String,
        recipient: String,
    ) {
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(300)).untilAsserted {
            val sentEmail =
                sentEmailJpaRepository
                    .findByAccountId(accountId)
                    .firstOrNull { it.eventType == eventType }
                    ?: throw AssertionError("The $eventType send record was not saved: accountId=$accountId")
            assertThat(sentEmail.recipient).isEqualTo(recipient)
            assertThat(sentEmail.sesMessageId).isNotBlank()

            val messages = fetchSesMessages()
            val matched =
                messages.firstOrNull { it["Id"] == sentEmail.sesMessageId }
                    ?: throw AssertionError("Could not find message sesMessageId=${sentEmail.sesMessageId} in localstack SES.")

            @Suppress("UNCHECKED_CAST")
            val destination = matched["Destination"] as Map<String, Any>

            @Suppress("UNCHECKED_CAST")
            val toAddresses = destination["ToAddresses"] as List<String>
            assertThat(toAddresses).contains(recipient)
        }
    }

    @Test
    fun `opening an account sends an AccountCreated notification email`() {
        val email = "notification-created@example.com"
        val accountId = createAccount("notification-owner-1", email)

        awaitEmailSent(accountId, "AccountCreated", email)
    }

    @Test
    fun `depositing sends a MoneyDeposited notification email`() {
        val email = "notification-deposit@example.com"
        val ownerId = "notification-owner-2"
        val accountId = createAccount(ownerId, email)

        val response = post("/accounts/$accountId/deposit", ownerId, mapOf("amount" to 10000))
        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)

        awaitEmailSent(accountId, "MoneyDeposited", email)
    }

    @Test
    fun `withdrawing sends a MoneyWithdrawn notification email`() {
        val email = "notification-withdraw@example.com"
        val ownerId = "notification-owner-3"
        val accountId = createAccount(ownerId, email)
        post("/accounts/$accountId/deposit", ownerId, mapOf("amount" to 10000))

        val response = post("/accounts/$accountId/withdraw", ownerId, mapOf("amount" to 4000))
        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)

        awaitEmailSent(accountId, "MoneyWithdrawn", email)
    }

    @Test
    fun `suspending an account sends an AccountSuspended notification email`() {
        val email = "notification-suspend@example.com"
        val ownerId = "notification-owner-4"
        val accountId = createAccount(ownerId, email)

        val response = post("/accounts/$accountId/suspend", ownerId, emptyMap())
        assertThat(response.statusCode).isEqualTo(HttpStatus.NO_CONTENT)

        awaitEmailSent(accountId, "AccountSuspended", email)
    }

    @Test
    fun `reactivating a suspended account sends an AccountReactivated notification email`() {
        val email = "notification-reactivate@example.com"
        val ownerId = "notification-owner-5"
        val accountId = createAccount(ownerId, email)
        post("/accounts/$accountId/suspend", ownerId, emptyMap())

        val response = post("/accounts/$accountId/reactivate", ownerId, emptyMap())
        assertThat(response.statusCode).isEqualTo(HttpStatus.NO_CONTENT)

        awaitEmailSent(accountId, "AccountReactivated", email)
    }

    @Test
    fun `closing an account with a 0 balance sends an AccountClosed notification email`() {
        val email = "notification-close@example.com"
        val ownerId = "notification-owner-6"
        val accountId = createAccount(ownerId, email)

        val response = post("/accounts/$accountId/close", ownerId, emptyMap())
        assertThat(response.statusCode).isEqualTo(HttpStatus.NO_CONTENT)

        awaitEmailSent(accountId, "AccountClosed", email)
    }
}

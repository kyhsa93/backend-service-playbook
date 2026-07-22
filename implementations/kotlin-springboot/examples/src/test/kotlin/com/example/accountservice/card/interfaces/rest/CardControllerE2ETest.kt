package com.example.accountservice.card.interfaces.rest

import com.example.accountservice.AccountServiceApplication
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
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
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest
import software.amazon.awssdk.services.sqs.model.QueueAttributeName
import java.time.Duration

/**
 * Card BC E2E tests — verifies the REST endpoints together with two actual cross-domain flows.
 *
 * 1. Synchronous Adapter/ACL: when a card is issued, AccountAdapter queries the Account BC to check
 *    whether it's active.
 * 2. Asynchronous Integration Event: suspending/closing an Account is reflected on the linked card
 *    (idempotently) via the Outbox → OutboxPoller (publishes to SQS) → OutboxConsumer (receives from
 *    SQS) → CardIntegrationEventController path. Since this is reflected up to a few seconds after
 *    the HTTP response comes back, we poll with [awaitCardStatus].
 *
 * SES is irrelevant to the Card BC (notification is an Account-only Technical Service) so it isn't
 * configured, but SQS must be configured in LocalStack since it is now shared infrastructure
 * (outbox/) across Account/Payment/Card.
 */
@Testcontainers
@SpringBootTest(
    classes = [AccountServiceApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class CardControllerE2ETest {
    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")

        @Container
        @JvmStatic
        val localstack: LocalStackContainer =
            LocalStackContainer(DockerImageName.parse("localstack/localstack:3.0"))
                .withServices(LocalStackContainer.Service.SQS)

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
            registry.add("AWS_ENDPOINT_URL") { localstack.getEndpointOverride(LocalStackContainer.Service.SQS).toString() }
            registry.add("SQS_DOMAIN_EVENT_QUEUE_URL") { createDomainEventQueue() }
            registry.add("SQS_TASK_QUEUE_URL") { createTaskQueue() }
            // The test calls the write API far more times in a short span than the default
            // limit-for-period (10), so for tests only we loosen it generously so we're verifying
            // each endpoint's logic rather than rate limiting itself.
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

        private const val OWNER_ID = "card-owner-1"
        private const val OTHER_OWNER_ID = "card-owner-2"
        private const val TEST_PASSWORD = "password123!"
    }

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

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
        currency: String = "KRW",
    ): Map<*, *> {
        val response =
            post(
                "/accounts",
                ownerId,
                mapOf("currency" to currency, "email" to "$ownerId@example.com"),
            )
        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        return response.body!!
    }

    private fun issueCard(
        ownerId: String,
        accountId: String,
        brand: String = "VISA",
    ): Map<*, *> {
        val response = post("/cards", ownerId, mapOf("accountId" to accountId, "brand" to brand))
        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        return response.body!!
    }

    /**
     * It takes up to a few seconds for the Integration Event (account.suspended.v1/
     * account.closed.v1) to be reflected in the card status via the Outbox → SQS → OutboxConsumer →
     * CardIntegrationEventController path — poll with a timeout more generous than the
     * LocalStack+SQS latency (2-4 seconds) actually measured in this repository.
     */
    private fun awaitCardStatus(
        cardId: String,
        ownerId: String,
        expectedStatus: String,
    ) {
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(300)).untilAsserted {
            val response = get("/cards/$cardId", ownerId)
            assertThat(response.body!!["status"]).isEqualTo(expectedStatus)
        }
    }

    @Test
    fun `issuing a card for an active account returns 201 and the ACTIVE card info`() {
        val account = createAccount(OWNER_ID)

        val response = post("/cards", OWNER_ID, mapOf("accountId" to (account["accountId"] as String), "brand" to "VISA"))

        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        val body = response.body!!
        assertThat(body["accountId"]).isEqualTo(account["accountId"])
        assertThat(body["ownerId"]).isEqualTo(OWNER_ID)
        assertThat(body["brand"]).isEqualTo("VISA")
        assertThat(body["status"]).isEqualTo("ACTIVE")
        assertThat(body["cardId"]).isNotNull()
    }

    @Test
    fun `returns 404 when issuing for a nonexistent account`() {
        val response = post("/cards", OWNER_ID, mapOf("accountId" to "non-existent", "brand" to "VISA"))

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `returns 404 when issuing for another owner's account`() {
        val account = createAccount(OWNER_ID)

        val response = post("/cards", OTHER_OWNER_ID, mapOf("accountId" to (account["accountId"] as String), "brand" to "VISA"))

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `returns 400 when issuing for a suspended account`() {
        val account = createAccount(OWNER_ID)
        post("/accounts/${account["accountId"]}/suspend", OWNER_ID, emptyMap())

        val response = post("/cards", OWNER_ID, mapOf("accountId" to (account["accountId"] as String), "brand" to "VISA"))

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `looking up an issued card returns 200 and the card info`() {
        val account = createAccount(OWNER_ID)
        val card = issueCard(OWNER_ID, account["accountId"] as String)

        val response = get("/cards/${card["cardId"]}", OWNER_ID)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body!!["cardId"]).isEqualTo(card["cardId"])
    }

    @Test
    fun `returns 404 when looking up a nonexistent card`() {
        val response = get("/cards/non-existent", OWNER_ID)

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `returns 404 when a different owner looks up a card`() {
        val account = createAccount(OWNER_ID)
        val card = issueCard(OWNER_ID, account["accountId"] as String)

        val response = get("/cards/${card["cardId"]}", OTHER_OWNER_ID)

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `suspending an account transitions all linked ACTIVE cards to SUSPENDED (Integration Event)`() {
        val account = createAccount(OWNER_ID)
        val card1 = issueCard(OWNER_ID, account["accountId"] as String)
        val card2 = issueCard(OWNER_ID, account["accountId"] as String, brand = "MASTER")

        val suspendResponse = post("/accounts/${account["accountId"]}/suspend", OWNER_ID, emptyMap())
        assertThat(suspendResponse.statusCode).isEqualTo(HttpStatus.NO_CONTENT)

        awaitCardStatus(card1["cardId"] as String, OWNER_ID, "SUSPENDED")
        awaitCardStatus(card2["cardId"] as String, OWNER_ID, "SUSPENDED")
    }

    @Test
    fun `an already-suspended card stays suspended after the account is reactivated (no subscription to that event)`() {
        val account = createAccount(OWNER_ID)
        val card = issueCard(OWNER_ID, account["accountId"] as String)
        post("/accounts/${account["accountId"]}/suspend", OWNER_ID, emptyMap())
        awaitCardStatus(card["cardId"] as String, OWNER_ID, "SUSPENDED")

        post("/accounts/${account["accountId"]}/reactivate", OWNER_ID, emptyMap())

        val cardAfter = get("/cards/${card["cardId"]}", OWNER_ID)
        assertThat(cardAfter.body!!["status"]).isEqualTo("SUSPENDED")
    }

    @Test
    fun `closing an account transitions all linked cards to CANCELLED (Integration Event)`() {
        val account = createAccount(OWNER_ID)
        val card = issueCard(OWNER_ID, account["accountId"] as String)

        val closeResponse = post("/accounts/${account["accountId"]}/close", OWNER_ID, emptyMap())
        assertThat(closeResponse.statusCode).isEqualTo(HttpStatus.NO_CONTENT)

        awaitCardStatus(card["cardId"] as String, OWNER_ID, "CANCELLED")
    }

    @Test
    fun `a card that was SUSPENDED also transitions to CANCELLED when the account is closed afterward (idempotent reaction)`() {
        val account = createAccount(OWNER_ID)
        val card = issueCard(OWNER_ID, account["accountId"] as String)
        post("/accounts/${account["accountId"]}/suspend", OWNER_ID, emptyMap())
        awaitCardStatus(card["cardId"] as String, OWNER_ID, "SUSPENDED")

        post("/accounts/${account["accountId"]}/close", OWNER_ID, emptyMap())

        awaitCardStatus(card["cardId"] as String, OWNER_ID, "CANCELLED")
    }
}

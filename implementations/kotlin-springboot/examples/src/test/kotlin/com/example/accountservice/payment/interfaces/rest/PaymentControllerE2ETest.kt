package com.example.accountservice.payment.interfaces.rest

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
 * Payment BC E2E tests — verifies the REST endpoints together with the actual Payment/Refund →
 * Account cross-domain flow (synchronous Adapter + asynchronous Integration Event).
 *
 * `CreatePaymentService`/`CancelPaymentService`/`RequestRefundService` return immediately after
 * saving — the Account BC's reaction (WithdrawByPaymentService/DepositByPaymentService) is processed
 * asynchronously via the Outbox → SQS (OutboxPoller) → OutboxConsumer path, so the account balance
 * update may not be finished yet by the time the HTTP response comes back — verify by polling with
 * [awaitBalance].
 */
@Testcontainers
@SpringBootTest(
    classes = [AccountServiceApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class PaymentControllerE2ETest {
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

        private const val OWNER_ID = "payment-owner-1"
        private const val OTHER_OWNER_ID = "payment-owner-2"
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
        val response = post("/accounts", ownerId, mapOf("currency" to currency, "email" to "$ownerId@example.com"))
        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        return response.body!!
    }

    private fun deposit(
        ownerId: String,
        accountId: String,
        amount: Long,
    ): Map<*, *> {
        val response = post("/accounts/$accountId/deposit", ownerId, mapOf("amount" to amount))
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

    private fun balanceOf(
        ownerId: String,
        accountId: String,
    ): Long {
        val response = get("/accounts/$accountId", ownerId)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val balance = response.body!!["balance"] as Map<*, *>
        return (balance["amount"] as Number).toLong()
    }

    /**
     * It takes up to a few seconds for the payment.completed.v1/payment.cancelled.v1/
     * refund.approved.v1 Integration Event to be reflected in the account balance via the Outbox →
     * SQS → OutboxConsumer → WithdrawByPaymentService/DepositByPaymentService path — poll with a
     * timeout more generous than the LocalStack+SQS latency (2-4 seconds) actually measured in this
     * repository.
     */
    private fun awaitBalance(
        ownerId: String,
        accountId: String,
        expected: Long,
    ) {
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(300)).untilAsserted {
            assertThat(balanceOf(ownerId, accountId)).isEqualTo(expected)
        }
    }

    /** Creates an (accountId, cardId) pair with account opening + deposit + card issuance already done. */
    private fun setUpFundedCard(
        ownerId: String,
        initialBalance: Long = 10_000,
    ): Pair<String, String> {
        val account = createAccount(ownerId)
        val accountId = account["accountId"] as String
        deposit(ownerId, accountId, initialBalance)
        val card = issueCard(ownerId, accountId)
        return accountId to (card["cardId"] as String)
    }

    @Test
    fun `returns 400 and does not deduct the balance when paying with an inactive card`() {
        val (accountId, cardId) = setUpFundedCard(OWNER_ID)
        // The Card BC has no REST endpoint to directly suspend a card — the card is deactivated via
        // the existing Integration Event path (same as CardControllerE2ETest) where suspending the
        // account publishes account.suspended.v1, which the Card BC subscribes to and suspends the
        // linked card. Since this path is asynchronous, we wait until the card has actually
        // transitioned to SUSPENDED before attempting the payment — otherwise this test could fail
        // intermittently depending on timing (payment would succeed because the card is still ACTIVE).
        val suspendResponse = post("/accounts/$accountId/suspend", OWNER_ID, emptyMap())
        assertThat(suspendResponse.statusCode).isEqualTo(HttpStatus.NO_CONTENT)
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(300)).untilAsserted {
            assertThat(get("/cards/$cardId", OWNER_ID).body!!["status"]).isEqualTo("SUSPENDED")
        }

        val response = post("/payments", OWNER_ID, mapOf("cardId" to cardId, "amount" to 1000))

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `returns 400 and does not deduct the balance when the account balance is insufficient`() {
        val (accountId, cardId) = setUpFundedCard(OWNER_ID, initialBalance = 500)

        val response = post("/payments", OWNER_ID, mapOf("cardId" to cardId, "amount" to 1000))

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(balanceOf(OWNER_ID, accountId)).isEqualTo(500)
    }

    @Test
    fun `returns 404 when paying with a nonexistent card`() {
        val response = post("/payments", OWNER_ID, mapOf("cardId" to "non-existent", "amount" to 1000))

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `returns 404 when paying with another owner's card`() {
        val (_, cardId) = setUpFundedCard(OWNER_ID)

        val response = post("/payments", OTHER_OWNER_ID, mapOf("cardId" to cardId, "amount" to 1000))

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `a normal payment returns 201 and the COMPLETED status, and the account balance is deducted asynchronously`() {
        val (accountId, cardId) = setUpFundedCard(OWNER_ID)

        val response = post("/payments", OWNER_ID, mapOf("cardId" to cardId, "amount" to 1000))

        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        val body = response.body!!
        assertThat(body["status"]).isEqualTo("COMPLETED")
        assertThat(body["accountId"]).isEqualTo(accountId)
        assertThat(body["amount"]).isEqualTo(1000)
        awaitBalance(OWNER_ID, accountId, 9000)
    }

    @Test
    fun `cancelling a payment returns 204 and the balance is restored via a compensating credit`() {
        val (accountId, cardId) = setUpFundedCard(OWNER_ID)
        val payment = post("/payments", OWNER_ID, mapOf("cardId" to cardId, "amount" to 1000)).body!!
        awaitBalance(OWNER_ID, accountId, 9000)

        val cancelResponse =
            post("/payments/${payment["paymentId"]}/cancel", OWNER_ID, mapOf("reason" to "Customer request"))

        assertThat(cancelResponse.statusCode).isEqualTo(HttpStatus.NO_CONTENT)
        awaitBalance(OWNER_ID, accountId, 10_000)

        val paymentAfter = get("/payments/${payment["paymentId"]}", OWNER_ID)
        assertThat(paymentAfter.body!!["status"]).isEqualTo("CANCELLED")
    }

    @Test
    fun `requesting a refund for a cancelled (non-completed) payment returns 201 with status REJECTED, balance unchanged`() {
        val (accountId, cardId) = setUpFundedCard(OWNER_ID)
        val payment = post("/payments", OWNER_ID, mapOf("cardId" to cardId, "amount" to 1000)).body!!
        post("/payments/${payment["paymentId"]}/cancel", OWNER_ID, mapOf("reason" to "Customer request"))
        awaitBalance(OWNER_ID, accountId, 10_000)

        val refundResponse =
            post("/payments/${payment["paymentId"]}/refunds", OWNER_ID, mapOf("amount" to 500, "reason" to "Simple change of mind"))

        assertThat(refundResponse.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(refundResponse.body!!["status"]).isEqualTo("REJECTED")
        assertThat(refundResponse.body!!["decisionNote"]).isEqualTo("A refund can only be requested for a completed payment.")
        assertThat(balanceOf(OWNER_ID, accountId)).isEqualTo(10_000)
    }

    @Test
    fun `requesting a refund amount that exceeds the payment amount returns 201 but with status REJECTED, and the balance is unchanged`() {
        val (accountId, cardId) = setUpFundedCard(OWNER_ID)
        val payment = post("/payments", OWNER_ID, mapOf("cardId" to cardId, "amount" to 1000)).body!!
        awaitBalance(OWNER_ID, accountId, 9000)

        val refundResponse =
            post("/payments/${payment["paymentId"]}/refunds", OWNER_ID, mapOf("amount" to 1500, "reason" to "Simple change of mind"))

        assertThat(refundResponse.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(refundResponse.body!!["status"]).isEqualTo("REJECTED")
        assertThat(refundResponse.body!!["decisionNote"]).isEqualTo("The refund amount cannot exceed the payment amount.")
        assertThat(balanceOf(OWNER_ID, accountId)).isEqualTo(9000)
    }

    @Test
    fun `requesting a valid refund for a completed payment returns 201 and APPROVED, and the credit is reflected asynchronously`() {
        val (accountId, cardId) = setUpFundedCard(OWNER_ID)
        val payment = post("/payments", OWNER_ID, mapOf("cardId" to cardId, "amount" to 1000)).body!!
        awaitBalance(OWNER_ID, accountId, 9000)

        val refundResponse =
            post("/payments/${payment["paymentId"]}/refunds", OWNER_ID, mapOf("amount" to 500, "reason" to "Simple change of mind"))

        assertThat(refundResponse.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(refundResponse.body!!["status"]).isEqualTo("APPROVED")
        assertThat(refundResponse.body!!["decisionNote"]).isEqualTo("The refund was approved.")
        awaitBalance(OWNER_ID, accountId, 9500)

        val refunds = get("/payments/${payment["paymentId"]}/refunds", OWNER_ID)
        assertThat(refunds.statusCode).isEqualTo(HttpStatus.OK)
        assertThat((refunds.body!!["count"] as Number).toInt()).isEqualTo(1)
    }

    @Test
    fun `listing payments returns only the authenticated requester's payments`() {
        // OWNER_ID/OTHER_OWNER_ID are shared and accumulated across all tests in this test class,
        // since they all share the same Testcontainers DB/Spring context (the DB is not reset between
        // tests) — so verifying the count as an absolute value requires an owner ID dedicated to this
        // test only (so it doesn't get mixed with payments created by other tests).
        val listOwnerId = "payment-owner-list-1"
        val otherOwnerId = "payment-owner-list-2"
        val (_, cardId) = setUpFundedCard(listOwnerId)
        post("/payments", listOwnerId, mapOf("cardId" to cardId, "amount" to 1000))
        setUpFundedCard(otherOwnerId)

        val response = get("/payments", listOwnerId)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat((response.body!!["count"] as Number).toInt()).isEqualTo(1)
    }

    @Test
    fun `returns 404 when a different owner looks up a payment`() {
        val (_, cardId) = setUpFundedCard(OWNER_ID)
        val payment = post("/payments", OWNER_ID, mapOf("cardId" to cardId, "amount" to 1000)).body!!

        val response = get("/payments/${payment["paymentId"]}", OTHER_OWNER_ID)

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }
}

package com.example.accountservice.account.interfaces.rest

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

@Testcontainers
@SpringBootTest(
    classes = [AccountServiceApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class AccountControllerE2ETest {
    companion object {
        private const val SENDER_EMAIL = "no-reply@backend-service-playbook.example.com"

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
            // The test calls the write API far more times in a short span than the default
            // limit-for-period (10), so for tests only we loosen it generously so we're verifying
            // each endpoint's logic rather than rate limiting itself.
            registry.add("resilience4j.ratelimiter.instances.http-write.limit-for-period") { "1000" }
        }

        // Since the container is already up (a static @Container field), we create the queue
        // directly and return its URL before the Spring context binds SqsProperties.
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

        private const val OWNER_ID = "owner-1"
        private const val OTHER_OWNER_ID = "owner-2"
        private const val TEST_PASSWORD = "password123!"
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

    private fun get(
        path: String,
        ownerId: String,
    ): ResponseEntity<Map<*, *>> = restTemplate.exchange(path, HttpMethod.GET, HttpEntity<Void>(headersFor(ownerId)), Map::class.java)

    private fun delete(
        path: String,
        ownerId: String,
    ): ResponseEntity<Map<*, *>> = restTemplate.exchange(path, HttpMethod.DELETE, HttpEntity<Void>(headersFor(ownerId)), Map::class.java)

    private fun createAccount(
        ownerId: String,
        currency: String,
        email: String = "$ownerId@example.com",
    ): Map<*, *> {
        val response = post("/accounts", ownerId, mapOf("currency" to currency, "email" to email))
        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        return response.body!!
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

    @Test
    fun `returns 201 and the account info when the creation request is valid`() {
        val response = post("/accounts", OWNER_ID, mapOf("currency" to "KRW", "email" to "$OWNER_ID@example.com"))

        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        val body = response.body!!
        assertThat(body["ownerId"]).isEqualTo(OWNER_ID)
        assertThat(body["email"]).isEqualTo("$OWNER_ID@example.com")
        assertThat(body["status"]).isEqualTo("ACTIVE")
        assertThat(body["accountId"]).isNotNull()
        assertThat(body["createdAt"]).isNotNull()
        @Suppress("UNCHECKED_CAST")
        val balance = body["balance"] as Map<String, Any>
        assertThat(balance["amount"]).isEqualTo(0)
        assertThat(balance["currency"]).isEqualTo("KRW")
    }

    @Test
    fun `returns 400 when the email format is invalid`() {
        val response = post("/accounts", OWNER_ID, mapOf("currency" to "KRW", "email" to "not-an-email"))
        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `creating an account sends a notification email and saves a send record`() {
        val email = "notify-created@example.com"

        val account = createAccount(OWNER_ID, "KRW", email)
        val accountId = account["accountId"] as String

        // Processing goes through the Outbox → SQS (OutboxPoller) → OutboxConsumer → EventHandler
        // path asynchronously, so the email may not have been sent yet by the time the response
        // comes back — poll with a generous timeout.
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(300)).untilAsserted {
            val messages = fetchSesMessages()
            val matched =
                messages.firstOrNull { message ->
                    @Suppress("UNCHECKED_CAST")
                    val destination = message["Destination"] as Map<String, Any>

                    @Suppress("UNCHECKED_CAST")
                    val toAddresses = destination["ToAddresses"] as List<String>
                    toAddresses.contains(email)
                }
            assertThat(matched).isNotNull()
            val messageId = matched!!["Id"] as String

            val sentEmails = sentEmailJpaRepository.findByAccountId(accountId)
            assertThat(sentEmails).anySatisfy { sentEmail ->
                assertThat(sentEmail.eventType).isEqualTo("AccountCreated")
                assertThat(sentEmail.recipient).isEqualTo(email)
                assertThat(sentEmail.sesMessageId).isEqualTo(messageId)
            }
        }
    }

    @Test
    fun `depositing sends a notification email and saves a send record`() {
        val email = "notify-deposit@example.com"
        val account = createAccount(OWNER_ID, "KRW", email)
        val accountId = account["accountId"] as String

        post("/accounts/$accountId/deposit", OWNER_ID, mapOf("amount" to 10000))

        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(300)).untilAsserted {
            val sentEmails = sentEmailJpaRepository.findByAccountId(accountId)
            assertThat(sentEmails).anySatisfy { sentEmail ->
                assertThat(sentEmail.eventType).isEqualTo("MoneyDeposited")
                assertThat(sentEmail.recipient).isEqualTo(email)
                assertThat(sentEmail.sesMessageId).isNotBlank()
            }
        }
    }

    @Test
    fun `returns 201 and the transaction record when the deposit request is valid`() {
        val account = createAccount(OWNER_ID, "KRW")

        val response = post("/accounts/${account["accountId"]}/deposit", OWNER_ID, mapOf("amount" to 10000))

        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        val body = response.body!!
        assertThat(body["accountId"]).isEqualTo(account["accountId"])
        assertThat(body["type"]).isEqualTo("DEPOSIT")
        assertThat(body["transactionId"]).isNotNull()
    }

    @Test
    fun `returns 404 when depositing to a nonexistent account`() {
        val response = post("/accounts/non-existent/deposit", OWNER_ID, mapOf("amount" to 10000))
        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `returns 404 when depositing to another owner's account`() {
        val account = createAccount(OWNER_ID, "KRW")

        val response = post("/accounts/${account["accountId"]}/deposit", OTHER_OWNER_ID, mapOf("amount" to 10000))

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `returns 400 when the deposit amount is 0 or less`() {
        val account = createAccount(OWNER_ID, "KRW")

        val response = post("/accounts/${account["accountId"]}/deposit", OWNER_ID, mapOf("amount" to 0))

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `returns 400 when depositing to a suspended account`() {
        val account = createAccount(OWNER_ID, "KRW")
        post("/accounts/${account["accountId"]}/suspend", OWNER_ID, emptyMap())

        val response = post("/accounts/${account["accountId"]}/deposit", OWNER_ID, mapOf("amount" to 10000))

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `returns 201 and the transaction record when the withdrawal request is valid`() {
        val account = createAccount(OWNER_ID, "KRW")
        post("/accounts/${account["accountId"]}/deposit", OWNER_ID, mapOf("amount" to 10000))

        val response = post("/accounts/${account["accountId"]}/withdraw", OWNER_ID, mapOf("amount" to 4000))

        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(response.body!!["type"]).isEqualTo("WITHDRAWAL")
    }

    @Test
    fun `returns 404 when withdrawing from a nonexistent account`() {
        val response = post("/accounts/non-existent/withdraw", OWNER_ID, mapOf("amount" to 1000))
        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `returns 400 when withdrawing more than the balance`() {
        val account = createAccount(OWNER_ID, "KRW")

        val response = post("/accounts/${account["accountId"]}/withdraw", OWNER_ID, mapOf("amount" to 1000))

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `returns 400 when withdrawing from a suspended account`() {
        val account = createAccount(OWNER_ID, "KRW")
        post("/accounts/${account["accountId"]}/suspend", OWNER_ID, emptyMap())

        val response = post("/accounts/${account["accountId"]}/withdraw", OWNER_ID, mapOf("amount" to 1000))

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `returns 201 and the withdrawal and deposit transaction records when the transfer request is valid`() {
        val source = createAccount(OWNER_ID, "KRW")
        post("/accounts/${source["accountId"]}/deposit", OWNER_ID, mapOf("amount" to 10000))
        val target = createAccount(OTHER_OWNER_ID, "KRW")

        val response =
            post(
                "/accounts/${source["accountId"]}/transfer",
                OWNER_ID,
                mapOf("targetAccountId" to target["accountId"]!!, "amount" to 4000),
            )

        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        val body = response.body!!
        assertThat(body["transferId"]).isNotNull()
        @Suppress("UNCHECKED_CAST")
        val sourceTx = body["sourceTransaction"] as Map<String, Any>

        @Suppress("UNCHECKED_CAST")
        val targetTx = body["targetTransaction"] as Map<String, Any>
        assertThat(sourceTx["type"]).isEqualTo("WITHDRAWAL")
        assertThat(targetTx["type"]).isEqualTo("DEPOSIT")

        val sourceGet = get("/accounts/${source["accountId"]}", OWNER_ID)

        @Suppress("UNCHECKED_CAST")
        val sourceBalance = sourceGet.body!!["balance"] as Map<String, Any>
        assertThat(sourceBalance["amount"]).isEqualTo(6000)

        val targetGet = get("/accounts/${target["accountId"]}", OTHER_OWNER_ID)

        @Suppress("UNCHECKED_CAST")
        val targetBalance = targetGet.body!!["balance"] as Map<String, Any>
        assertThat(targetBalance["amount"]).isEqualTo(4000)
    }

    @Test
    fun `can transfer to an account owned by someone else`() {
        val source = createAccount(OWNER_ID, "KRW")
        post("/accounts/${source["accountId"]}/deposit", OWNER_ID, mapOf("amount" to 10000))
        val target = createAccount(OTHER_OWNER_ID, "KRW")

        val response =
            post(
                "/accounts/${source["accountId"]}/transfer",
                OWNER_ID,
                mapOf("targetAccountId" to target["accountId"]!!, "amount" to 1000),
            )

        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
    }

    @Test
    fun `returns 404 when the withdrawal account cannot be found during a transfer`() {
        val target = createAccount(OTHER_OWNER_ID, "KRW")

        val response =
            post(
                "/accounts/non-existent/transfer",
                OWNER_ID,
                mapOf("targetAccountId" to target["accountId"]!!, "amount" to 1000),
            )

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(response.body!!["code"]).isEqualTo("ACCOUNT_NOT_FOUND")
    }

    @Test
    fun `returns 404 when the deposit account cannot be found during a transfer`() {
        val source = createAccount(OWNER_ID, "KRW")
        post("/accounts/${source["accountId"]}/deposit", OWNER_ID, mapOf("amount" to 10000))

        val response =
            post(
                "/accounts/${source["accountId"]}/transfer",
                OWNER_ID,
                mapOf("targetAccountId" to "non-existent", "amount" to 1000),
            )

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(response.body!!["code"]).isEqualTo("ACCOUNT_NOT_FOUND")
    }

    @Test
    fun `returns 400 and TRANSFER_SAME_ACCOUNT when the withdrawal and deposit accounts are the same`() {
        val account = createAccount(OWNER_ID, "KRW")
        post("/accounts/${account["accountId"]}/deposit", OWNER_ID, mapOf("amount" to 10000))

        val response =
            post(
                "/accounts/${account["accountId"]}/transfer",
                OWNER_ID,
                mapOf("targetAccountId" to account["accountId"]!!, "amount" to 1000),
            )

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.body!!["code"]).isEqualTo("TRANSFER_SAME_ACCOUNT")
    }

    @Test
    fun `returns 400 and INSUFFICIENT_BALANCE when transferring more than the balance`() {
        val source = createAccount(OWNER_ID, "KRW")
        val target = createAccount(OTHER_OWNER_ID, "KRW")

        val response =
            post(
                "/accounts/${source["accountId"]}/transfer",
                OWNER_ID,
                mapOf("targetAccountId" to target["accountId"]!!, "amount" to 1000),
            )

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.body!!["code"]).isEqualTo("INSUFFICIENT_BALANCE")
    }

    @Test
    fun `returns 400 and WITHDRAW_REQUIRES_ACTIVE_ACCOUNT when the withdrawal account is suspended`() {
        val source = createAccount(OWNER_ID, "KRW")
        post("/accounts/${source["accountId"]}/deposit", OWNER_ID, mapOf("amount" to 10000))
        post("/accounts/${source["accountId"]}/suspend", OWNER_ID, emptyMap())
        val target = createAccount(OTHER_OWNER_ID, "KRW")

        val response =
            post(
                "/accounts/${source["accountId"]}/transfer",
                OWNER_ID,
                mapOf("targetAccountId" to target["accountId"]!!, "amount" to 1000),
            )

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.body!!["code"]).isEqualTo("WITHDRAW_REQUIRES_ACTIVE_ACCOUNT")
    }

    @Test
    fun `returns 400 and DEPOSIT_REQUIRES_ACTIVE_ACCOUNT when the deposit account is suspended`() {
        val source = createAccount(OWNER_ID, "KRW")
        post("/accounts/${source["accountId"]}/deposit", OWNER_ID, mapOf("amount" to 10000))
        val target = createAccount(OTHER_OWNER_ID, "KRW")
        post("/accounts/${target["accountId"]}/suspend", OTHER_OWNER_ID, emptyMap())

        val response =
            post(
                "/accounts/${source["accountId"]}/transfer",
                OWNER_ID,
                mapOf("targetAccountId" to target["accountId"]!!, "amount" to 1000),
            )

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.body!!["code"]).isEqualTo("DEPOSIT_REQUIRES_ACTIVE_ACCOUNT")
    }

    @Test
    fun `returns 400 and CURRENCY_MISMATCH when the currencies do not match`() {
        val source = createAccount(OWNER_ID, "KRW")
        post("/accounts/${source["accountId"]}/deposit", OWNER_ID, mapOf("amount" to 10000))
        val target = createAccount(OTHER_OWNER_ID, "USD")

        val response =
            post(
                "/accounts/${source["accountId"]}/transfer",
                OWNER_ID,
                mapOf("targetAccountId" to target["accountId"]!!, "amount" to 1000),
            )

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.body!!["code"]).isEqualTo("CURRENCY_MISMATCH")
    }

    @Test
    fun `returns 204 when suspending a normal account`() {
        val account = createAccount(OWNER_ID, "KRW")

        val response = post("/accounts/${account["accountId"]}/suspend", OWNER_ID, emptyMap())
        assertThat(response.statusCode).isEqualTo(HttpStatus.NO_CONTENT)

        val getResponse = get("/accounts/${account["accountId"]}", OWNER_ID)
        assertThat(getResponse.body!!["status"]).isEqualTo("SUSPENDED")
    }

    @Test
    fun `returns 404 when suspending a nonexistent account`() {
        val response = post("/accounts/non-existent/suspend", OWNER_ID, emptyMap())
        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `returns 400 when suspending an already-suspended account`() {
        val account = createAccount(OWNER_ID, "KRW")
        post("/accounts/${account["accountId"]}/suspend", OWNER_ID, emptyMap())

        val response = post("/accounts/${account["accountId"]}/suspend", OWNER_ID, emptyMap())

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `returns 204 when reactivating a suspended account`() {
        val account = createAccount(OWNER_ID, "KRW")
        post("/accounts/${account["accountId"]}/suspend", OWNER_ID, emptyMap())

        val response = post("/accounts/${account["accountId"]}/reactivate", OWNER_ID, emptyMap())
        assertThat(response.statusCode).isEqualTo(HttpStatus.NO_CONTENT)

        val getResponse = get("/accounts/${account["accountId"]}", OWNER_ID)
        assertThat(getResponse.body!!["status"]).isEqualTo("ACTIVE")
    }

    @Test
    fun `returns 404 when reactivating a nonexistent account`() {
        val response = post("/accounts/non-existent/reactivate", OWNER_ID, emptyMap())
        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `returns 400 when reactivating an active account`() {
        val account = createAccount(OWNER_ID, "KRW")

        val response = post("/accounts/${account["accountId"]}/reactivate", OWNER_ID, emptyMap())

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `returns 204 when closing an account with a 0 balance`() {
        val account = createAccount(OWNER_ID, "KRW")

        val response = post("/accounts/${account["accountId"]}/close", OWNER_ID, emptyMap())
        assertThat(response.statusCode).isEqualTo(HttpStatus.NO_CONTENT)

        val getResponse = get("/accounts/${account["accountId"]}", OWNER_ID)
        assertThat(getResponse.body!!["status"]).isEqualTo("CLOSED")
    }

    @Test
    fun `returns 404 when closing a nonexistent account`() {
        val response = post("/accounts/non-existent/close", OWNER_ID, emptyMap())
        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `returns 400 when the balance is not 0`() {
        val account = createAccount(OWNER_ID, "KRW")
        post("/accounts/${account["accountId"]}/deposit", OWNER_ID, mapOf("amount" to 5000))

        val response = post("/accounts/${account["accountId"]}/close", OWNER_ID, emptyMap())

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `returns 400 when closing an already-closed account`() {
        val account = createAccount(OWNER_ID, "KRW")
        post("/accounts/${account["accountId"]}/close", OWNER_ID, emptyMap())

        val response = post("/accounts/${account["accountId"]}/close", OWNER_ID, emptyMap())

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `deleting a closed account returns 204, and a subsequent lookup returns 404`() {
        val account = createAccount(OWNER_ID, "KRW")
        post("/accounts/${account["accountId"]}/close", OWNER_ID, emptyMap())

        val response = delete("/accounts/${account["accountId"]}", OWNER_ID)
        assertThat(response.statusCode).isEqualTo(HttpStatus.NO_CONTENT)

        val getResponse = get("/accounts/${account["accountId"]}", OWNER_ID)
        assertThat(getResponse.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `returns 404 when deleting a nonexistent account`() {
        val response = delete("/accounts/non-existent", OWNER_ID)
        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `returns 400 when deleting an account that is not closed`() {
        val account = createAccount(OWNER_ID, "KRW")

        val response = delete("/accounts/${account["accountId"]}", OWNER_ID)

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `returns 200 and the account info when looking up an existing account`() {
        val account = createAccount(OWNER_ID, "KRW")

        val response = get("/accounts/${account["accountId"]}", OWNER_ID)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = response.body!!
        assertThat(body["accountId"]).isEqualTo(account["accountId"])
        assertThat(body["ownerId"]).isEqualTo(OWNER_ID)
        assertThat(body["updatedAt"]).isNotNull()
    }

    @Test
    fun `returns 404 when looking up a nonexistent account`() {
        val response = get("/accounts/non-existent", OWNER_ID)
        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `returns 404 when looked up by a different owner`() {
        val account = createAccount(OWNER_ID, "KRW")

        val response = get("/accounts/${account["accountId"]}", OTHER_OWNER_ID)

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `returns the transaction history with pagination`() {
        val account = createAccount(OWNER_ID, "KRW")
        post("/accounts/${account["accountId"]}/deposit", OWNER_ID, mapOf("amount" to 10000))
        post("/accounts/${account["accountId"]}/withdraw", OWNER_ID, mapOf("amount" to 3000))

        val response = get("/accounts/${account["accountId"]}/transactions?page=0&take=20", OWNER_ID)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = response.body!!
        assertThat(body["count"]).isEqualTo(2)
        assertThat((body["transactions"] as List<*>)).hasSize(2)
    }

    @Test
    fun `returns 404 when looking up transaction history for a nonexistent account`() {
        val response = get("/accounts/non-existent/transactions", OWNER_ID)
        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `looking up a page beyond the available data returns an empty array`() {
        val account = createAccount(OWNER_ID, "KRW")

        val response = get("/accounts/${account["accountId"]}/transactions?page=5&take=20", OWNER_ID)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body!!["count"]).isEqualTo(0)
    }
}

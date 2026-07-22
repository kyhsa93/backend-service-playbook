package com.example.accountservice.auth.interfaces.rest

import com.example.accountservice.AccountServiceApplication
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpStatus
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory
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

/**
 * Auth BC E2E tests.
 *
 * Verifies that sign-up stores a Credential, that sign-in actually compares against the stored
 * hash, that a nonexistent user ID and a mismatched password are unified into the same response
 * (401 INVALID_CREDENTIALS, to prevent user enumeration), and that duplicate sign-up rejection and
 * password-length validation work.
 *
 * The Auth BC itself has nothing to do with Outbox/Task Queue/SQS, but since both
 * `SqsProperties.domainEventQueueUrl` and `taskQueueUrl` are fail-fast validated (`@NotBlank`), this
 * test — which boots the whole app context — must also set up both queues (the standard domain-event
 * queue and the Task Queue FIFO queue) in LocalStack SQS; otherwise the context itself cannot come up
 * before the `OutboxPoller`/`OutboxConsumer`/`TaskOutboxPoller`/`TaskQueueConsumer` beans are created.
 */
@Testcontainers
@SpringBootTest(
    classes = [AccountServiceApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class AuthControllerE2ETest {
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
            // The test calls the write API more times in a short span than the default
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

        private const val PASSWORD = "password123!"
    }

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    // The JDK's default HttpURLConnection-based client throws a "cannot retry due to server
    // authentication, in streaming mode" IOException when it gets a 401 response after a POST (this
    // is JDK behavior itself, not a bug in this app — SimpleClientHttpRequestFactory, which
    // TestRestTemplate uses by default, is exposed to this issue). We swap in Apache HttpClient5 as
    // the request factory, which doesn't have this limitation.
    @BeforeEach
    fun useHttpComponentsClient() {
        restTemplate.restTemplate.requestFactory = HttpComponentsClientHttpRequestFactory()
    }

    private fun signUp(
        userId: String,
        password: String = PASSWORD,
    ) = restTemplate.postForEntity("/auth/sign-up", mapOf("userId" to userId, "password" to password), Map::class.java)

    private fun signIn(
        userId: String,
        password: String,
    ) = restTemplate.postForEntity("/auth/sign-in", mapOf("userId" to userId, "password" to password), Map::class.java)

    @Test
    fun `signing in after sign-up returns 200 and an access token`() {
        val signUpResponse = signUp("auth-owner-1")
        assertThat(signUpResponse.statusCode).isEqualTo(HttpStatus.CREATED)

        val signInResponse = signIn("auth-owner-1", PASSWORD)

        assertThat(signInResponse.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(signInResponse.body!!["accessToken"]).isNotNull()
    }

    @Test
    fun `returns 401 and INVALID_CREDENTIALS when the password is wrong`() {
        signUp("auth-owner-2")

        val response = signIn("auth-owner-2", "wrong-password")

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(response.body!!["code"]).isEqualTo("INVALID_CREDENTIALS")
    }

    @Test
    fun `returns 401 and INVALID_CREDENTIALS when logging in with a nonexistent user ID`() {
        val response = signIn("no-such-user", PASSWORD)

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(response.body!!["code"]).isEqualTo("INVALID_CREDENTIALS")
    }

    @Test
    fun `a nonexistent user ID and a wrong password return the same response (prevents user enumeration)`() {
        signUp("auth-owner-3")

        val wrongPassword = signIn("auth-owner-3", "wrong-password")
        val noSuchUser = signIn("no-such-user-2", PASSWORD)

        assertThat(wrongPassword.statusCode).isEqualTo(noSuchUser.statusCode)
        assertThat(wrongPassword.body!!["code"]).isEqualTo(noSuchUser.body!!["code"])
        assertThat(wrongPassword.body!!["message"]).isEqualTo(noSuchUser.body!!["message"])
    }

    @Test
    fun `returns 400 and USER_ID_ALREADY_EXISTS when signing up with a user ID already in use`() {
        signUp("auth-owner-4")

        val response = signUp("auth-owner-4", "another-password")

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.body!!["code"]).isEqualTo("USER_ID_ALREADY_EXISTS")
    }

    @Test
    fun `returns 400 and VALIDATION_FAILED when the password is under 8 characters`() {
        val response = signUp("auth-owner-5", "short")

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.body!!["code"]).isEqualTo("VALIDATION_FAILED")
    }
}

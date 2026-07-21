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
 * Auth BC E2E 테스트.
 *
 * sign-up으로 Credential을 저장하고, sign-in이 저장된 해시와 실제로 비교하는지,
 * 아이디 미존재/비밀번호 불일치가 동일한 응답(401 INVALID_CREDENTIALS)으로 통일되는지
 * (user enumeration 방지), 아이디 중복 가입과 비밀번호 길이 검증이 동작하는지 확인한다.
 *
 * Auth BC 자체는 Outbox/Task Queue/SQS와 무관하지만, `SqsProperties.domainEventQueueUrl`/
 * `taskQueueUrl` 모두 fail-fast(`@NotBlank`)로 검증되므로 전체 앱 컨텍스트를 띄우는 이 테스트도
 * LocalStack SQS에 두 큐(도메인 이벤트 표준 큐 + Task Queue FIFO 큐)를 구성해야 한다 — 그렇지
 * 않으면 `OutboxPoller`/`OutboxConsumer`/`TaskOutboxPoller`/`TaskQueueConsumer` 빈 생성 전에
 * 컨텍스트 자체가 뜨지 못한다.
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
            // 테스트는 짧은 시간 안에 write API를 기본 limit-for-period(10)보다 많이 호출하므로
            // rate limiting 자체가 아니라 각 엔드포인트 로직을 검증할 수 있도록 테스트 한정으로 넉넉하게 푼다.
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

        // SqsProperties.taskQueueUrl도 @NotBlank(config.md fail-fast)이므로, Task Queue 경로를
        // 실제로 쓰지 않는 이 테스트도 컨텍스트 기동을 위해 FIFO 큐를 만들어 둔다(TaskQueueE2ETest와
        // 동일한 방식).
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

    // JDK 기본 HttpURLConnection 기반 클라이언트는 POST 후 401 응답을 받으면 "cannot retry due to
    // server authentication, in streaming mode" IOException을 던진다(JDK 자체 동작, 이 앱의 버그
    // 아님 — TestRestTemplate이 기본으로 쓰는 SimpleClientHttpRequestFactory가 이 문제에 노출된다).
    // 이 제약이 없는 Apache HttpClient5로 요청 팩토리를 교체한다.
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
    fun `sign-up 후 sign-in하면 200과 액세스 토큰을 반환한다`() {
        val signUpResponse = signUp("auth-owner-1")
        assertThat(signUpResponse.statusCode).isEqualTo(HttpStatus.CREATED)

        val signInResponse = signIn("auth-owner-1", PASSWORD)

        assertThat(signInResponse.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(signInResponse.body!!["accessToken"]).isNotNull()
    }

    @Test
    fun `비밀번호가 틀리면 401과 INVALID_CREDENTIALS를 반환한다`() {
        signUp("auth-owner-2")

        val response = signIn("auth-owner-2", "wrong-password")

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(response.body!!["code"]).isEqualTo("INVALID_CREDENTIALS")
    }

    @Test
    fun `존재하지 않는 아이디로 로그인하면 401과 INVALID_CREDENTIALS를 반환한다`() {
        val response = signIn("no-such-user", PASSWORD)

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(response.body!!["code"]).isEqualTo("INVALID_CREDENTIALS")
    }

    @Test
    fun `존재하지 않는 아이디와 틀린 비밀번호는 동일한 응답을 반환한다 (user enumeration 방지)`() {
        signUp("auth-owner-3")

        val wrongPassword = signIn("auth-owner-3", "wrong-password")
        val noSuchUser = signIn("no-such-user-2", PASSWORD)

        assertThat(wrongPassword.statusCode).isEqualTo(noSuchUser.statusCode)
        assertThat(wrongPassword.body!!["code"]).isEqualTo(noSuchUser.body!!["code"])
        assertThat(wrongPassword.body!!["message"]).isEqualTo(noSuchUser.body!!["message"])
    }

    @Test
    fun `이미 사용 중인 아이디로 가입하면 400과 USER_ID_ALREADY_EXISTS를 반환한다`() {
        signUp("auth-owner-4")

        val response = signUp("auth-owner-4", "another-password")

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.body!!["code"]).isEqualTo("USER_ID_ALREADY_EXISTS")
    }

    @Test
    fun `비밀번호가 8자 미만이면 400과 VALIDATION_FAILED를 반환한다`() {
        val response = signUp("auth-owner-5", "short")

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.body!!["code"]).isEqualTo("VALIDATION_FAILED")
    }
}

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
 * scheduling.md가 규정하는 전체 경로(Scheduler → task_outbox → TaskOutboxPoller → SQS FIFO →
 * TaskQueueConsumer → TaskHandlerRegistry → TaskController → CommandService)를 실제로 태워보는
 * e2e 테스트다. 실제 Cron tick(최대 하루/한 달)을 기다리지 않고, Scheduler의 enqueue 메서드를
 * 직접 호출한다 — [com.example.accountservice.account.notification.NotificationE2ETest]가 Outbox
 * 경로를 검증하는 것과 같은 방식으로 Task Queue 경로를 검증한다.
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
            // 테스트는 짧은 시간 안에 write API를 기본 limit-for-period(10)보다 많이 호출하므로
            // rate limiting 자체가 아니라 Task Queue 경로를 검증할 수 있도록 테스트 한정으로 넉넉하게 푼다.
            registry.add("resilience4j.ratelimiter.instances.http-write.limit-for-period") { "1000" }
        }

        // 컨테이너는 이미 떠 있는 상태이므로(정적 @Container 필드), Spring 컨텍스트가 SqsProperties를
        // 바인딩하기 전에 큐를 직접 만들어 그 URL을 반환한다 — DLQ/RedrivePolicy는 테스트 목적상
        // 생략한다(재시도 관찰이 필요 없다).
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
    fun `정기 이자 지급 Task를 enqueue하면 잔액에 이자가 반영되고 같은 날짜로 재실행해도 중복 지급되지 않는다`() {
        val ownerId = "interest-owner-1"
        val email = "interest-owner-1@example.com"
        val accountId = createAccount(ownerId, email)
        // 이자율 0.01%에서 정수 이자가 나오도록 충분히 큰 잔액을 입금한다: 10,000,000 * 0.0001 = 1,000
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

        // 같은 날짜로 다시 enqueue해도(at-least-once 재전달을 흉내) task_outbox의 deduplicationId
        // UNIQUE 제약이 중복 적재를 막고, 설령 처리되더라도 Account.payInterest()가 멱등하므로 이자가
        // 두 번 지급되지 않는다.
        interestPaymentScheduler.enqueueDailyInterestPayment()

        Thread.sleep(3000)
        val transactionsAfterRetry = get("/accounts/$accountId/transactions", ownerId).body!!

        @Suppress("UNCHECKED_CAST")
        val listAfterRetry = transactionsAfterRetry["transactions"] as List<Map<String, Any>>
        assertThat(listAfterRetry.count { it["type"] == "INTEREST" }).isEqualTo(1)

        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(300)).untilAsserted {
            val interestEmail =
                sentEmailJpaRepository.findByAccountId(accountId).firstOrNull { it.eventType == "InterestPaid" }
                    ?: throw AssertionError("InterestPaid 발송 기록이 저장되지 않았습니다: accountId=$accountId")
            assertThat(interestEmail.recipient).isEqualTo(email)
        }
    }

    @Test
    fun `매월 카드 사용내역 발송 Task를 enqueue하면 결제 요약 알림이 발송되고 이번 달에 재실행해도 중복 발송되지 않는다`() {
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
                    ?: throw AssertionError("CardStatement 발송 기록이 저장되지 않았습니다: accountId=$accountId")
            assertThat(statementEmail.recipient).isEqualTo(email)
        }

        // 같은 달에 다시 enqueue해도 Card.lastStatementSentMonth(Level 1) + task_outbox
        // deduplicationId(다중 인스턴스 안전성)가 중복 발송을 막는다.
        cardStatementScheduler.enqueueMonthlyCardStatement()

        Thread.sleep(3000)
        val statementEmailsAfterRetry = sentEmailJpaRepository.findByAccountId(accountId).filter { it.eventType == "CardStatement" }
        assertThat(statementEmailsAfterRetry).hasSize(1)
    }
}

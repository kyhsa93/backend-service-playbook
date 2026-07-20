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
import java.time.Duration

/**
 * Card BC E2E 테스트 — REST 엔드포인트와 함께 두 크로스 도메인 흐름을 실제로 검증한다.
 *
 * 1. 동기 Adapter/ACL: 카드 발급 시 AccountAdapter가 Account BC를 조회해 활성 여부를 확인한다.
 * 2. 비동기 Integration Event: Account 정지/해지가 Outbox → OutboxPoller(SQS 발행) →
 *    OutboxConsumer(SQS 수신) → CardIntegrationEventController 경로를 거쳐 연결된 카드에 반영된다
 *    (멱등성 포함). HTTP 응답이 돌아온 뒤 최대 몇 초 뒤에 반영되므로 [awaitCardStatus]로 폴링한다.
 *
 * SES는 Card BC와 무관하므로(알림은 Account만의 Technical Service) 구성하지 않지만, SQS는 이제
 * Account/Payment/Card가 공유하는 인프라(outbox/)이므로 LocalStack에 구성해야 한다.
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
            // 테스트는 짧은 시간 안에 write API를 기본 limit-for-period(10)보다 훨씬 많이 호출하므로
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
     * Integration Event(account.suspended.v1/account.closed.v1)가 Outbox → SQS → OutboxConsumer →
     * CardIntegrationEventController 경로를 거쳐 카드 상태에 반영되기까지 최대 몇 초가 걸린다 —
     * 이 저장소가 실측한 LocalStack+SQS 지연(2~4초)보다 넉넉한 타임아웃으로 폴링한다.
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
    fun `활성 계좌로 카드를 발급하면 201과 ACTIVE 카드 정보를 반환한다`() {
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
    fun `존재하지 않는 계좌로 발급하면 404를 반환한다`() {
        val response = post("/cards", OWNER_ID, mapOf("accountId" to "non-existent", "brand" to "VISA"))

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `다른 소유자의 계좌로 발급하면 404를 반환한다`() {
        val account = createAccount(OWNER_ID)

        val response = post("/cards", OTHER_OWNER_ID, mapOf("accountId" to (account["accountId"] as String), "brand" to "VISA"))

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `정지된 계좌로 발급하면 400을 반환한다`() {
        val account = createAccount(OWNER_ID)
        post("/accounts/${account["accountId"]}/suspend", OWNER_ID, emptyMap())

        val response = post("/cards", OWNER_ID, mapOf("accountId" to (account["accountId"] as String), "brand" to "VISA"))

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `발급한 카드를 조회하면 200과 카드 정보를 반환한다`() {
        val account = createAccount(OWNER_ID)
        val card = issueCard(OWNER_ID, account["accountId"] as String)

        val response = get("/cards/${card["cardId"]}", OWNER_ID)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body!!["cardId"]).isEqualTo(card["cardId"])
    }

    @Test
    fun `존재하지 않는 카드를 조회하면 404를 반환한다`() {
        val response = get("/cards/non-existent", OWNER_ID)

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `다른 소유자가 카드를 조회하면 404를 반환한다`() {
        val account = createAccount(OWNER_ID)
        val card = issueCard(OWNER_ID, account["accountId"] as String)

        val response = get("/cards/${card["cardId"]}", OTHER_OWNER_ID)

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `계좌를 정지하면 연결된 ACTIVE 카드가 모두 SUSPENDED로 전환된다 (Integration Event)`() {
        val account = createAccount(OWNER_ID)
        val card1 = issueCard(OWNER_ID, account["accountId"] as String)
        val card2 = issueCard(OWNER_ID, account["accountId"] as String, brand = "MASTER")

        val suspendResponse = post("/accounts/${account["accountId"]}/suspend", OWNER_ID, emptyMap())
        assertThat(suspendResponse.statusCode).isEqualTo(HttpStatus.NO_CONTENT)

        awaitCardStatus(card1["cardId"] as String, OWNER_ID, "SUSPENDED")
        awaitCardStatus(card2["cardId"] as String, OWNER_ID, "SUSPENDED")
    }

    @Test
    fun `계좌를 재개해도 이미 정지된 카드는 그대로 유지된다 (카드는 재개 이벤트를 구독하지 않음)`() {
        val account = createAccount(OWNER_ID)
        val card = issueCard(OWNER_ID, account["accountId"] as String)
        post("/accounts/${account["accountId"]}/suspend", OWNER_ID, emptyMap())
        awaitCardStatus(card["cardId"] as String, OWNER_ID, "SUSPENDED")

        post("/accounts/${account["accountId"]}/reactivate", OWNER_ID, emptyMap())

        val cardAfter = get("/cards/${card["cardId"]}", OWNER_ID)
        assertThat(cardAfter.body!!["status"]).isEqualTo("SUSPENDED")
    }

    @Test
    fun `계좌를 종료하면 연결된 카드가 모두 CANCELLED로 전환된다 (Integration Event)`() {
        val account = createAccount(OWNER_ID)
        val card = issueCard(OWNER_ID, account["accountId"] as String)

        val closeResponse = post("/accounts/${account["accountId"]}/close", OWNER_ID, emptyMap())
        assertThat(closeResponse.statusCode).isEqualTo(HttpStatus.NO_CONTENT)

        awaitCardStatus(card["cardId"] as String, OWNER_ID, "CANCELLED")
    }

    @Test
    fun `정지 후 종료하면 SUSPENDED였던 카드도 CANCELLED로 전환된다 (멱등 반응)`() {
        val account = createAccount(OWNER_ID)
        val card = issueCard(OWNER_ID, account["accountId"] as String)
        post("/accounts/${account["accountId"]}/suspend", OWNER_ID, emptyMap())
        awaitCardStatus(card["cardId"] as String, OWNER_ID, "SUSPENDED")

        post("/accounts/${account["accountId"]}/close", OWNER_ID, emptyMap())

        awaitCardStatus(card["cardId"] as String, OWNER_ID, "CANCELLED")
    }
}

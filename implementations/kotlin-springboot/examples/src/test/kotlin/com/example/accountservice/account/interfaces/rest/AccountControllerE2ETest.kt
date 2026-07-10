package com.example.accountservice.account.interfaces.rest

import com.example.accountservice.AccountServiceApplication
import com.example.accountservice.notification.infrastructure.persistence.SentEmailJpaRepository
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
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
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

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
                .withServices(LocalStackContainer.Service.SES)

        @DynamicPropertySource
        @JvmStatic
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.jpa.hibernate.ddl-auto") { "create-drop" }
            registry.add("AWS_REGION") { localstack.region }
            registry.add("AWS_ACCESS_KEY_ID") { localstack.accessKey }
            registry.add("AWS_SECRET_ACCESS_KEY") { localstack.secretKey }
            registry.add("AWS_ENDPOINT_URL") { localstack.getEndpointOverride(LocalStackContainer.Service.SES).toString() }
        }

        @BeforeAll
        @JvmStatic
        fun verifySesSender() {
            // LocalStack의 SES 에뮬레이터도 실제 SES처럼 발신자 신원 검증을 강제하므로,
            // 테스트에서 이메일을 보내기 전에 발신 주소를 미리 인증해 둔다.
            val sesClient = SesClient.builder()
                .region(Region.of(localstack.region))
                .credentialsProvider(
                    StaticCredentialsProvider.create(AwsBasicCredentials.create(localstack.accessKey, localstack.secretKey)),
                )
                .endpointOverride(localstack.getEndpointOverride(LocalStackContainer.Service.SES))
                .build()
            sesClient.verifyEmailIdentity(VerifyEmailIdentityRequest.builder().emailAddress(SENDER_EMAIL).build())
            sesClient.close()
        }

        private const val OWNER_ID = "owner-1"
        private const val OTHER_OWNER_ID = "owner-2"
    }

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var sentEmailJpaRepository: SentEmailJpaRepository

    private val tokenCache = mutableMapOf<String, String>()

    private fun tokenFor(userId: String): String = tokenCache.getOrPut(userId) {
        val response = restTemplate.postForEntity("/auth/sign-in", mapOf("userId" to userId), Map::class.java)
        response.body!!["accessToken"] as String
    }

    private fun headersFor(ownerId: String): HttpHeaders {
        val headers = HttpHeaders()
        headers.setBearerAuth(tokenFor(ownerId))
        headers.contentType = MediaType.APPLICATION_JSON
        return headers
    }

    private fun post(path: String, ownerId: String, body: Map<String, Any>): ResponseEntity<Map<*, *>> =
        restTemplate.exchange(path, HttpMethod.POST, HttpEntity(body, headersFor(ownerId)), Map::class.java)

    private fun get(path: String, ownerId: String): ResponseEntity<Map<*, *>> =
        restTemplate.exchange(path, HttpMethod.GET, HttpEntity<Void>(headersFor(ownerId)), Map::class.java)

    private fun createAccount(ownerId: String, currency: String, email: String = "$ownerId@example.com"): Map<*, *> {
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
    fun `생성 요청이 유효하면 201과 계좌 정보를 반환한다`() {
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
    fun `이메일 형식이 올바르지 않으면 400을 반환한다`() {
        val response = post("/accounts", OWNER_ID, mapOf("currency" to "KRW", "email" to "not-an-email"))
        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `계좌를 생성하면 알림 이메일이 발송되고 발송 기록이 저장된다`() {
        val email = "notify-created@example.com"

        val account = createAccount(OWNER_ID, "KRW", email)
        val accountId = account["accountId"] as String

        val messages = fetchSesMessages()
        val matched = messages.firstOrNull { message ->
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

    @Test
    fun `입금하면 알림 이메일이 발송되고 발송 기록이 저장된다`() {
        val email = "notify-deposit@example.com"
        val account = createAccount(OWNER_ID, "KRW", email)
        val accountId = account["accountId"] as String

        post("/accounts/$accountId/deposit", OWNER_ID, mapOf("amount" to 10000))

        val sentEmails = sentEmailJpaRepository.findByAccountId(accountId)
        assertThat(sentEmails).anySatisfy { sentEmail ->
            assertThat(sentEmail.eventType).isEqualTo("MoneyDeposited")
            assertThat(sentEmail.recipient).isEqualTo(email)
            assertThat(sentEmail.sesMessageId).isNotBlank()
        }
    }

    @Test
    fun `입금 요청이 유효하면 201과 거래 내역을 반환한다`() {
        val account = createAccount(OWNER_ID, "KRW")

        val response = post("/accounts/${account["accountId"]}/deposit", OWNER_ID, mapOf("amount" to 10000))

        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        val body = response.body!!
        assertThat(body["accountId"]).isEqualTo(account["accountId"])
        assertThat(body["type"]).isEqualTo("DEPOSIT")
        assertThat(body["transactionId"]).isNotNull()
    }

    @Test
    fun `입금 시 존재하지 않는 계좌면 404를 반환한다`() {
        val response = post("/accounts/non-existent/deposit", OWNER_ID, mapOf("amount" to 10000))
        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `입금 시 다른 소유자의 계좌면 404를 반환한다`() {
        val account = createAccount(OWNER_ID, "KRW")

        val response = post("/accounts/${account["accountId"]}/deposit", OTHER_OWNER_ID, mapOf("amount" to 10000))

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `입금 금액이 0 이하이면 400을 반환한다`() {
        val account = createAccount(OWNER_ID, "KRW")

        val response = post("/accounts/${account["accountId"]}/deposit", OWNER_ID, mapOf("amount" to 0))

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `정지된 계좌에 입금하면 400을 반환한다`() {
        val account = createAccount(OWNER_ID, "KRW")
        post("/accounts/${account["accountId"]}/suspend", OWNER_ID, emptyMap())

        val response = post("/accounts/${account["accountId"]}/deposit", OWNER_ID, mapOf("amount" to 10000))

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `출금 요청이 유효하면 201과 거래 내역을 반환한다`() {
        val account = createAccount(OWNER_ID, "KRW")
        post("/accounts/${account["accountId"]}/deposit", OWNER_ID, mapOf("amount" to 10000))

        val response = post("/accounts/${account["accountId"]}/withdraw", OWNER_ID, mapOf("amount" to 4000))

        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(response.body!!["type"]).isEqualTo("WITHDRAWAL")
    }

    @Test
    fun `출금 시 존재하지 않는 계좌면 404를 반환한다`() {
        val response = post("/accounts/non-existent/withdraw", OWNER_ID, mapOf("amount" to 1000))
        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `잔액보다 큰 금액을 출금하면 400을 반환한다`() {
        val account = createAccount(OWNER_ID, "KRW")

        val response = post("/accounts/${account["accountId"]}/withdraw", OWNER_ID, mapOf("amount" to 1000))

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `정지된 계좌에서 출금하면 400을 반환한다`() {
        val account = createAccount(OWNER_ID, "KRW")
        post("/accounts/${account["accountId"]}/suspend", OWNER_ID, emptyMap())

        val response = post("/accounts/${account["accountId"]}/withdraw", OWNER_ID, mapOf("amount" to 1000))

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `정상 계좌를 정지하면 204를 반환한다`() {
        val account = createAccount(OWNER_ID, "KRW")

        val response = post("/accounts/${account["accountId"]}/suspend", OWNER_ID, emptyMap())
        assertThat(response.statusCode).isEqualTo(HttpStatus.NO_CONTENT)

        val getResponse = get("/accounts/${account["accountId"]}", OWNER_ID)
        assertThat(getResponse.body!!["status"]).isEqualTo("SUSPENDED")
    }

    @Test
    fun `정지 시 존재하지 않는 계좌면 404를 반환한다`() {
        val response = post("/accounts/non-existent/suspend", OWNER_ID, emptyMap())
        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `이미 정지된 계좌를 정지하면 400을 반환한다`() {
        val account = createAccount(OWNER_ID, "KRW")
        post("/accounts/${account["accountId"]}/suspend", OWNER_ID, emptyMap())

        val response = post("/accounts/${account["accountId"]}/suspend", OWNER_ID, emptyMap())

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `정지된 계좌를 재개하면 204를 반환한다`() {
        val account = createAccount(OWNER_ID, "KRW")
        post("/accounts/${account["accountId"]}/suspend", OWNER_ID, emptyMap())

        val response = post("/accounts/${account["accountId"]}/reactivate", OWNER_ID, emptyMap())
        assertThat(response.statusCode).isEqualTo(HttpStatus.NO_CONTENT)

        val getResponse = get("/accounts/${account["accountId"]}", OWNER_ID)
        assertThat(getResponse.body!!["status"]).isEqualTo("ACTIVE")
    }

    @Test
    fun `재개 시 존재하지 않는 계좌면 404를 반환한다`() {
        val response = post("/accounts/non-existent/reactivate", OWNER_ID, emptyMap())
        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `활성 계좌를 재개하면 400을 반환한다`() {
        val account = createAccount(OWNER_ID, "KRW")

        val response = post("/accounts/${account["accountId"]}/reactivate", OWNER_ID, emptyMap())

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `잔액이 0인 계좌를 종료하면 204를 반환한다`() {
        val account = createAccount(OWNER_ID, "KRW")

        val response = post("/accounts/${account["accountId"]}/close", OWNER_ID, emptyMap())
        assertThat(response.statusCode).isEqualTo(HttpStatus.NO_CONTENT)

        val getResponse = get("/accounts/${account["accountId"]}", OWNER_ID)
        assertThat(getResponse.body!!["status"]).isEqualTo("CLOSED")
    }

    @Test
    fun `종료 시 존재하지 않는 계좌면 404를 반환한다`() {
        val response = post("/accounts/non-existent/close", OWNER_ID, emptyMap())
        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `잔액이 0이 아니면 400을 반환한다`() {
        val account = createAccount(OWNER_ID, "KRW")
        post("/accounts/${account["accountId"]}/deposit", OWNER_ID, mapOf("amount" to 5000))

        val response = post("/accounts/${account["accountId"]}/close", OWNER_ID, emptyMap())

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `이미 종료된 계좌를 종료하면 400을 반환한다`() {
        val account = createAccount(OWNER_ID, "KRW")
        post("/accounts/${account["accountId"]}/close", OWNER_ID, emptyMap())

        val response = post("/accounts/${account["accountId"]}/close", OWNER_ID, emptyMap())

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `존재하는 계좌를 조회하면 200과 계좌 정보를 반환한다`() {
        val account = createAccount(OWNER_ID, "KRW")

        val response = get("/accounts/${account["accountId"]}", OWNER_ID)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = response.body!!
        assertThat(body["accountId"]).isEqualTo(account["accountId"])
        assertThat(body["ownerId"]).isEqualTo(OWNER_ID)
        assertThat(body["updatedAt"]).isNotNull()
    }

    @Test
    fun `조회 시 존재하지 않는 계좌면 404를 반환한다`() {
        val response = get("/accounts/non-existent", OWNER_ID)
        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `다른 소유자가 조회하면 404를 반환한다`() {
        val account = createAccount(OWNER_ID, "KRW")

        val response = get("/accounts/${account["accountId"]}", OTHER_OWNER_ID)

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `거래 내역을 페이지네이션과 함께 반환한다`() {
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
    fun `거래 내역 조회 시 존재하지 않는 계좌면 404를 반환한다`() {
        val response = get("/accounts/non-existent/transactions", OWNER_ID)
        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `take를 초과한 페이지 조회는 빈 배열을 반환한다`() {
        val account = createAccount(OWNER_ID, "KRW")

        val response = get("/accounts/${account["accountId"]}/transactions?page=5&take=20", OWNER_ID)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body!!["count"]).isEqualTo(0)
    }
}

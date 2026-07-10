package com.example.accountservice.notification

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

/**
 * Account 도메인 커맨드(개설/입금/출금/정지/재개/종료) 각각이 Outbox 경로를 통해 SES로 알림
 * 이메일을 발송하는지 검증한다.
 *
 * [com.example.accountservice.account.interfaces.rest.AccountControllerE2ETest]가 이미 계좌
 * 개설/입금 알림을 커버하지만, 이 클래스는 6개 커맨드 전체를 대상으로 알림 발송을 전담 검증한다 —
 * `outbox/` 도입(OutboxWriter가 Aggregate 저장과 같은 트랜잭션에 이벤트를 커밋하고,
 * OutboxRelay.processPending()이 커밋 직후 동기적으로 이를 드레인해 `application/event/`의
 * `*EventHandler`를 호출하는 경로)에 대한 회귀 테스트이기도 하다.
 */
@Testcontainers
@SpringBootTest(
    classes = [AccountServiceApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class NotificationE2ETest {

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
    }

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var sentEmailJpaRepository: SentEmailJpaRepository

    private fun tokenFor(userId: String): String {
        val response = restTemplate.postForEntity("/auth/sign-in", mapOf("userId" to userId), Map::class.java)
        return response.body!!["accessToken"] as String
    }

    private fun headersFor(ownerId: String): HttpHeaders {
        val headers = HttpHeaders()
        headers.setBearerAuth(tokenFor(ownerId))
        headers.contentType = MediaType.APPLICATION_JSON
        return headers
    }

    private fun post(path: String, ownerId: String, body: Map<String, Any>): ResponseEntity<Map<*, *>> =
        restTemplate.exchange(path, HttpMethod.POST, HttpEntity(body, headersFor(ownerId)), Map::class.java)

    private fun createAccount(ownerId: String, email: String, currency: String = "KRW"): String {
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

    /** DB에 저장된 발송 기록과 LocalStack SES에 실제로 도착한 메시지를 모두 검증한다. */
    private fun assertEmailSent(accountId: String, eventType: String, recipient: String) {
        val sentEmail = sentEmailJpaRepository.findByAccountId(accountId)
            .firstOrNull { it.eventType == eventType }
            ?: throw AssertionError("$eventType 발송 기록이 저장되지 않았습니다: accountId=$accountId")
        assertThat(sentEmail.recipient).isEqualTo(recipient)
        assertThat(sentEmail.sesMessageId).isNotBlank()

        val messages = fetchSesMessages()
        val matched = messages.firstOrNull { it["Id"] == sentEmail.sesMessageId }
            ?: throw AssertionError("localstack SES에서 sesMessageId=${sentEmail.sesMessageId} 메시지를 찾을 수 없습니다.")
        @Suppress("UNCHECKED_CAST")
        val destination = matched["Destination"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val toAddresses = destination["ToAddresses"] as List<String>
        assertThat(toAddresses).contains(recipient)
    }

    @Test
    fun `계좌를 개설하면 AccountCreated 알림 이메일이 발송된다`() {
        val email = "notification-created@example.com"
        val accountId = createAccount("notification-owner-1", email)

        assertEmailSent(accountId, "AccountCreated", email)
    }

    @Test
    fun `입금하면 MoneyDeposited 알림 이메일이 발송된다`() {
        val email = "notification-deposit@example.com"
        val ownerId = "notification-owner-2"
        val accountId = createAccount(ownerId, email)

        val response = post("/accounts/$accountId/deposit", ownerId, mapOf("amount" to 10000))
        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)

        assertEmailSent(accountId, "MoneyDeposited", email)
    }

    @Test
    fun `출금하면 MoneyWithdrawn 알림 이메일이 발송된다`() {
        val email = "notification-withdraw@example.com"
        val ownerId = "notification-owner-3"
        val accountId = createAccount(ownerId, email)
        post("/accounts/$accountId/deposit", ownerId, mapOf("amount" to 10000))

        val response = post("/accounts/$accountId/withdraw", ownerId, mapOf("amount" to 4000))
        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)

        assertEmailSent(accountId, "MoneyWithdrawn", email)
    }

    @Test
    fun `계좌를 정지하면 AccountSuspended 알림 이메일이 발송된다`() {
        val email = "notification-suspend@example.com"
        val ownerId = "notification-owner-4"
        val accountId = createAccount(ownerId, email)

        val response = post("/accounts/$accountId/suspend", ownerId, emptyMap())
        assertThat(response.statusCode).isEqualTo(HttpStatus.NO_CONTENT)

        assertEmailSent(accountId, "AccountSuspended", email)
    }

    @Test
    fun `정지된 계좌를 재개하면 AccountReactivated 알림 이메일이 발송된다`() {
        val email = "notification-reactivate@example.com"
        val ownerId = "notification-owner-5"
        val accountId = createAccount(ownerId, email)
        post("/accounts/$accountId/suspend", ownerId, emptyMap())

        val response = post("/accounts/$accountId/reactivate", ownerId, emptyMap())
        assertThat(response.statusCode).isEqualTo(HttpStatus.NO_CONTENT)

        assertEmailSent(accountId, "AccountReactivated", email)
    }

    @Test
    fun `잔액이 0인 계좌를 종료하면 AccountClosed 알림 이메일이 발송된다`() {
        val email = "notification-close@example.com"
        val ownerId = "notification-owner-6"
        val accountId = createAccount(ownerId, email)

        val response = post("/accounts/$accountId/close", ownerId, emptyMap())
        assertThat(response.statusCode).isEqualTo(HttpStatus.NO_CONTENT)

        assertEmailSent(accountId, "AccountClosed", email)
    }
}

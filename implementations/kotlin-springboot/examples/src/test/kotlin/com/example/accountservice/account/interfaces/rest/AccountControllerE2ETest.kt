package com.example.accountservice.account.interfaces.rest

import com.example.accountservice.AccountServiceApplication
import org.assertj.core.api.Assertions.assertThat
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
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers
@SpringBootTest(
    classes = [AccountServiceApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class AccountControllerE2ETest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")

        @DynamicPropertySource
        @JvmStatic
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.jpa.hibernate.ddl-auto") { "create-drop" }
        }

        private const val OWNER_ID = "owner-1"
        private const val OTHER_OWNER_ID = "owner-2"
    }

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private fun headersFor(ownerId: String): HttpHeaders {
        val headers = HttpHeaders()
        headers.set("X-User-Id", ownerId)
        headers.contentType = MediaType.APPLICATION_JSON
        return headers
    }

    private fun post(path: String, ownerId: String, body: Map<String, Any>): ResponseEntity<Map<*, *>> =
        restTemplate.exchange(path, HttpMethod.POST, HttpEntity(body, headersFor(ownerId)), Map::class.java)

    private fun get(path: String, ownerId: String): ResponseEntity<Map<*, *>> =
        restTemplate.exchange(path, HttpMethod.GET, HttpEntity<Void>(headersFor(ownerId)), Map::class.java)

    private fun createAccount(ownerId: String, currency: String): Map<*, *> {
        val response = post("/accounts", ownerId, mapOf("currency" to currency))
        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        return response.body!!
    }

    @Test
    fun `생성 요청이 유효하면 201과 계좌 정보를 반환한다`() {
        val response = post("/accounts", OWNER_ID, mapOf("currency" to "KRW"))

        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        val body = response.body!!
        assertThat(body["ownerId"]).isEqualTo(OWNER_ID)
        assertThat(body["status"]).isEqualTo("ACTIVE")
        assertThat(body["accountId"]).isNotNull()
        assertThat(body["createdAt"]).isNotNull()
        @Suppress("UNCHECKED_CAST")
        val balance = body["balance"] as Map<String, Any>
        assertThat(balance["amount"]).isEqualTo(0)
        assertThat(balance["currency"]).isEqualTo("KRW")
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

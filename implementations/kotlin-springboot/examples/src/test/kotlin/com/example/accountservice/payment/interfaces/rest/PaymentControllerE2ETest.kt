package com.example.accountservice.payment.interfaces.rest

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

/**
 * Payment BC E2E 테스트 — REST 엔드포인트와 함께 Payment/Refund → Account 크로스 도메인 흐름
 * (동기 Adapter + 비동기 Integration Event)을 실제로 검증한다.
 *
 * `CreatePaymentService`/`CancelPaymentService`/`RequestRefundService`는 Aggregate 저장 트랜잭션
 * 커밋 직후 `OutboxRelay.processPending()`을 동기 호출하고, 그 드레인 루프가 같은 호출 안에서
 * Account BC의 반응(WithdrawByPaymentService/DepositByPaymentService)까지 끝까지 처리하므로,
 * HTTP 응답이 돌아온 시점에는 이미 계좌 잔액 반영이 끝나 있다 — 별도의 폴링/대기가 필요 없다
 * (CardControllerE2ETest와 동일한 동기 검증 스타일).
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

        @DynamicPropertySource
        @JvmStatic
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.jpa.hibernate.ddl-auto") { "create-drop" }
            registry.add("spring.flyway.enabled") { "false" }
            registry.add("resilience4j.ratelimiter.instances.http-write.limit-for-period") { "1000" }
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

    /** 계좌 개설 + 충전 + 카드 발급까지 끝낸 (accountId, cardId) 조합을 만든다. */
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
    fun `비활성 카드로 결제하면 400을 반환하고 잔액은 차감되지 않는다`() {
        val (accountId, cardId) = setUpFundedCard(OWNER_ID)
        // Card BC는 카드를 직접 정지하는 REST 엔드포인트가 없다 — 계좌를 정지하면 account.suspended.v1을
        // Card BC가 구독해 연결된 카드를 정지시키는 기존 Integration Event 경로(CardControllerE2ETest와
        // 동일)로 카드를 비활성화한다.
        val suspendResponse = post("/accounts/$accountId/suspend", OWNER_ID, emptyMap())
        assertThat(suspendResponse.statusCode).isEqualTo(HttpStatus.NO_CONTENT)

        val response = post("/payments", OWNER_ID, mapOf("cardId" to cardId, "amount" to 1000))

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `계좌 잔액이 부족하면 400을 반환하고 잔액은 차감되지 않는다`() {
        val (accountId, cardId) = setUpFundedCard(OWNER_ID, initialBalance = 500)

        val response = post("/payments", OWNER_ID, mapOf("cardId" to cardId, "amount" to 1000))

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(balanceOf(OWNER_ID, accountId)).isEqualTo(500)
    }

    @Test
    fun `존재하지 않는 카드로 결제하면 404를 반환한다`() {
        val response = post("/payments", OWNER_ID, mapOf("cardId" to "non-existent", "amount" to 1000))

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `다른 소유자의 카드로 결제하면 404를 반환한다`() {
        val (_, cardId) = setUpFundedCard(OWNER_ID)

        val response = post("/payments", OTHER_OWNER_ID, mapOf("cardId" to cardId, "amount" to 1000))

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `정상 결제하면 201과 COMPLETED 상태를 반환하고 계좌 잔액이 비동기로 차감된다`() {
        val (accountId, cardId) = setUpFundedCard(OWNER_ID)

        val response = post("/payments", OWNER_ID, mapOf("cardId" to cardId, "amount" to 1000))

        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        val body = response.body!!
        assertThat(body["status"]).isEqualTo("COMPLETED")
        assertThat(body["accountId"]).isEqualTo(accountId)
        assertThat(body["amount"]).isEqualTo(1000)
        // 응답이 돌아온 시점에 이미 OutboxRelay 드레인이 끝나 있으므로 별도 대기 없이 확인한다.
        assertThat(balanceOf(OWNER_ID, accountId)).isEqualTo(9000)
    }

    @Test
    fun `결제취소하면 204를 반환하고 보상 크레딧으로 잔액이 복구된다`() {
        val (accountId, cardId) = setUpFundedCard(OWNER_ID)
        val payment = post("/payments", OWNER_ID, mapOf("cardId" to cardId, "amount" to 1000)).body!!
        assertThat(balanceOf(OWNER_ID, accountId)).isEqualTo(9000)

        val cancelResponse =
            post("/payments/${payment["paymentId"]}/cancel", OWNER_ID, mapOf("reason" to "고객 요청"))

        assertThat(cancelResponse.statusCode).isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(balanceOf(OWNER_ID, accountId)).isEqualTo(10_000)

        val paymentAfter = get("/payments/${payment["paymentId"]}", OWNER_ID)
        assertThat(paymentAfter.body!!["status"]).isEqualTo("CANCELLED")
    }

    @Test
    fun `완료되지 않은(취소된) 결제에 환불을 요청하면 201이지만 REJECTED로 응답하고 잔액은 그대로다`() {
        val (accountId, cardId) = setUpFundedCard(OWNER_ID)
        val payment = post("/payments", OWNER_ID, mapOf("cardId" to cardId, "amount" to 1000)).body!!
        post("/payments/${payment["paymentId"]}/cancel", OWNER_ID, mapOf("reason" to "고객 요청"))
        assertThat(balanceOf(OWNER_ID, accountId)).isEqualTo(10_000)

        val refundResponse =
            post("/payments/${payment["paymentId"]}/refunds", OWNER_ID, mapOf("amount" to 500, "reason" to "단순 변심"))

        assertThat(refundResponse.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(refundResponse.body!!["status"]).isEqualTo("REJECTED")
        assertThat(refundResponse.body!!["decisionNote"]).isEqualTo("완료된 결제에 대해서만 환불을 요청할 수 있습니다.")
        assertThat(balanceOf(OWNER_ID, accountId)).isEqualTo(10_000)
    }

    @Test
    fun `환불 금액이 결제 금액을 초과하면 201이지만 REJECTED로 응답하고 잔액은 그대로다`() {
        val (accountId, cardId) = setUpFundedCard(OWNER_ID)
        val payment = post("/payments", OWNER_ID, mapOf("cardId" to cardId, "amount" to 1000)).body!!
        assertThat(balanceOf(OWNER_ID, accountId)).isEqualTo(9000)

        val refundResponse =
            post("/payments/${payment["paymentId"]}/refunds", OWNER_ID, mapOf("amount" to 1500, "reason" to "단순 변심"))

        assertThat(refundResponse.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(refundResponse.body!!["status"]).isEqualTo("REJECTED")
        assertThat(refundResponse.body!!["decisionNote"]).isEqualTo("환불 금액은 결제 금액을 초과할 수 없습니다.")
        assertThat(balanceOf(OWNER_ID, accountId)).isEqualTo(9000)
    }

    @Test
    fun `완료된 결제에 대해 유효한 환불을 요청하면 201과 APPROVED를 반환하고 크레딧이 비동기로 반영된다`() {
        val (accountId, cardId) = setUpFundedCard(OWNER_ID)
        val payment = post("/payments", OWNER_ID, mapOf("cardId" to cardId, "amount" to 1000)).body!!
        assertThat(balanceOf(OWNER_ID, accountId)).isEqualTo(9000)

        val refundResponse =
            post("/payments/${payment["paymentId"]}/refunds", OWNER_ID, mapOf("amount" to 500, "reason" to "단순 변심"))

        assertThat(refundResponse.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(refundResponse.body!!["status"]).isEqualTo("APPROVED")
        assertThat(refundResponse.body!!["decisionNote"]).isEqualTo("환불이 승인되었습니다.")
        assertThat(balanceOf(OWNER_ID, accountId)).isEqualTo(9500)

        val refunds = get("/payments/${payment["paymentId"]}/refunds", OWNER_ID)
        assertThat(refunds.statusCode).isEqualTo(HttpStatus.OK)
        assertThat((refunds.body!!["count"] as Number).toInt()).isEqualTo(1)
    }

    @Test
    fun `결제 목록 조회는 인증된 요청자의 결제만 반환한다`() {
        // OWNER_ID/OTHER_OWNER_ID는 이 테스트 클래스의 모든 테스트가 같은 Testcontainers DB·Spring
        // 컨텍스트를 공유하며 누적해서 쓴다(각 테스트 사이에 DB를 리셋하지 않는다) — 그래서 개수(count)를
        // 절대값으로 검증하려면 이 테스트만의 전용 소유자 ID가 필요하다(다른 테스트가 만든 결제와
        // 섞이지 않도록).
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
    fun `다른 소유자가 결제를 조회하면 404를 반환한다`() {
        val (_, cardId) = setUpFundedCard(OWNER_ID)
        val payment = post("/payments", OWNER_ID, mapOf("cardId" to cardId, "amount" to 1000)).body!!

        val response = get("/payments/${payment["paymentId"]}", OTHER_OWNER_ID)

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }
}

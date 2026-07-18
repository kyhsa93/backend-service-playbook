package com.example.accountservice.payment.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.accountservice.AccountServiceApplication;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Payment BC의 E2E 테스트. Card+Account 두 BC를 동기 Adapter(ACL)로 조율하는 결제 생성과, Payment↔Account 양방향
 * Integration Event(payment.completed.v1 → 차감, payment.cancelled.v1/refund.approved.v1 → 보상 크레딧),
 * 그리고 RefundEligibilityService(Domain Service, Payment+Refund 두 Aggregate를 조율하는 순수 판단 로직)를 실제 HTTP
 * API + Postgres로 검증한다.
 *
 * <p>{@code CreatePaymentService}/{@code CancelPaymentService}/{@code RequestRefundService}가 저장 직후
 * {@code OutboxRelay.processPending()}을 호출하므로 이 흐름은 HTTP 응답이 오기 전에 동기적으로 완결된다 — {@code
 * CardControllerE2ETest}와 동일하게 별도의 폴링/대기가 필요 없다.
 */
@Testcontainers
@SuppressWarnings("unchecked")
@SpringBootTest(
        classes = AccountServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaymentControllerE2ETest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add(
                "resilience4j.ratelimiter.instances.createAccount.limit-for-period", () -> "1000");
        registry.add(
                "resilience4j.ratelimiter.instances.http-write.limit-for-period", () -> "1000");
    }

    @Autowired private TestRestTemplate restTemplate;

    private static final String OWNER_ID = "payment-owner-1";
    private static final String OTHER_OWNER_ID = "payment-owner-2";
    private static final String PASSWORD = "password123!";

    private final Map<String, String> tokenCache = new ConcurrentHashMap<>();

    private String tokenFor(String userId) {
        return tokenCache.computeIfAbsent(
                userId,
                id -> {
                    restTemplate.postForEntity(
                            "/auth/sign-up", Map.of("userId", id, "password", PASSWORD), Map.class);
                    ResponseEntity<Map> response =
                            restTemplate.postForEntity(
                                    "/auth/sign-in",
                                    Map.of("userId", id, "password", PASSWORD),
                                    Map.class);
                    return (String) response.getBody().get("accessToken");
                });
    }

    private HttpHeaders headersFor(String ownerId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tokenFor(ownerId));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ResponseEntity<Map> post(String path, String ownerId, Map<String, Object> body) {
        return restTemplate.exchange(
                path, HttpMethod.POST, new HttpEntity<>(body, headersFor(ownerId)), Map.class);
    }

    private ResponseEntity<Map> get(String path, String ownerId) {
        return restTemplate.exchange(
                path, HttpMethod.GET, new HttpEntity<>(headersFor(ownerId)), Map.class);
    }

    private Map<String, Object> createAccount(String ownerId) {
        ResponseEntity<Map> response =
                post(
                        "/accounts",
                        ownerId,
                        Map.of("currency", "KRW", "email", ownerId + "@example.com"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private void deposit(String accountId, long amount, String ownerId) {
        ResponseEntity<Map> response =
                post("/accounts/" + accountId + "/deposit", ownerId, Map.of("amount", amount));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private Map<String, Object> issueCard(String ownerId, String accountId) {
        ResponseEntity<Map> response =
                post("/cards", ownerId, Map.of("accountId", accountId, "brand", "VISA"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private ResponseEntity<Map> createPayment(String cardId, long amount, String ownerId) {
        return post("/payments", ownerId, Map.of("cardId", cardId, "amount", amount));
    }

    private long getBalance(String accountId, String ownerId) {
        Map<String, Object> balance =
                (Map<String, Object>)
                        get("/accounts/" + accountId, ownerId).getBody().get("balance");
        return ((Number) balance.get("amount")).longValue();
    }

    @Test
    void 비활성_카드로_결제하면_400과_PAYMENT_REQUIRES_ACTIVE_CARD를_반환한다() {
        Map<String, Object> account = createAccount(OWNER_ID);
        String accountId = (String) account.get("accountId");
        deposit(accountId, 50000, OWNER_ID);
        Map<String, Object> card = issueCard(OWNER_ID, accountId);
        // 카드 전용 정지 엔드포인트는 없다 — 계좌를 정지시켜 연결 카드가 account.suspended.v1에
        // 반응해 SUSPENDED로 전환되게 한다(동기적으로 완결되므로 폴링이 필요 없다).
        ResponseEntity<Map> suspendResponse =
                post("/accounts/" + accountId + "/suspend", OWNER_ID, Map.of());
        assertThat(suspendResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(get("/cards/" + card.get("cardId"), OWNER_ID).getBody().get("status"))
                .isEqualTo("SUSPENDED");

        ResponseEntity<Map> response = createPayment((String) card.get("cardId"), 10000, OWNER_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("PAYMENT_REQUIRES_ACTIVE_CARD");
    }

    @Test
    void 잔액이_부족하면_400과_INSUFFICIENT_BALANCE를_반환한다() {
        Map<String, Object> account = createAccount(OWNER_ID);
        String accountId = (String) account.get("accountId");
        deposit(accountId, 1000, OWNER_ID);
        Map<String, Object> card = issueCard(OWNER_ID, accountId);

        ResponseEntity<Map> response = createPayment((String) card.get("cardId"), 5000, OWNER_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("INSUFFICIENT_BALANCE");
    }

    @Test
    void 존재하지_않는_카드면_404와_LINKED_CARD_NOT_FOUND를_반환한다() {
        ResponseEntity<Map> response = createPayment("non-existent-card", 1000, OWNER_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("LINKED_CARD_NOT_FOUND");
    }

    @Test
    void 다른_소유자의_카드로_결제하면_404를_반환한다() {
        Map<String, Object> account = createAccount(OWNER_ID);
        String accountId = (String) account.get("accountId");
        deposit(accountId, 50000, OWNER_ID);
        Map<String, Object> card = issueCard(OWNER_ID, accountId);

        ResponseEntity<Map> response =
                createPayment((String) card.get("cardId"), 1000, OTHER_OWNER_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("LINKED_CARD_NOT_FOUND");
    }

    @Test
    void 활성_카드와_충분한_잔액이면_201과_COMPLETED_결제를_반환하고_계좌_잔액이_차감된다() {
        Map<String, Object> account = createAccount(OWNER_ID);
        String accountId = (String) account.get("accountId");
        deposit(accountId, 50000, OWNER_ID);
        Map<String, Object> card = issueCard(OWNER_ID, accountId);

        ResponseEntity<Map> response = createPayment((String) card.get("cardId"), 10000, OWNER_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> body = response.getBody();
        assertThat(body.get("cardId")).isEqualTo(card.get("cardId"));
        assertThat(body.get("accountId")).isEqualTo(accountId);
        assertThat(body.get("ownerId")).isEqualTo(OWNER_ID);
        assertThat(((Number) body.get("amount")).longValue()).isEqualTo(10000);
        assertThat(body.get("status")).isEqualTo("COMPLETED");

        // payment.completed.v1을 Account BC가 구독해 비동기(같은 트랜잭션 안에서 동기적으로 완결)로 차감한다.
        assertThat(getBalance(accountId, OWNER_ID)).isEqualTo(40000);
    }

    @Test
    void 완료된_결제를_취소하면_204를_반환하고_계좌_잔액이_복구된다() {
        Map<String, Object> account = createAccount(OWNER_ID);
        String accountId = (String) account.get("accountId");
        deposit(accountId, 50000, OWNER_ID);
        Map<String, Object> card = issueCard(OWNER_ID, accountId);
        Map<String, Object> payment =
                createPayment((String) card.get("cardId"), 10000, OWNER_ID).getBody();
        assertThat(getBalance(accountId, OWNER_ID)).isEqualTo(40000);

        ResponseEntity<Map> cancelResponse =
                post(
                        "/payments/" + payment.get("paymentId") + "/cancel",
                        OWNER_ID,
                        Map.of("reason", "고객 요청"));

        assertThat(cancelResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(getBalance(accountId, OWNER_ID)).isEqualTo(50000);
        assertThat(get("/payments/" + payment.get("paymentId"), OWNER_ID).getBody().get("status"))
                .isEqualTo("CANCELLED");
    }

    @Test
    void 존재하지_않는_결제를_취소하면_404를_반환한다() {
        ResponseEntity<Map> response =
                post("/payments/non-existent/cancel", OWNER_ID, Map.of("reason", "사유"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("PAYMENT_NOT_FOUND");
    }

    @Test
    void 이미_취소된_결제를_다시_취소하면_400과_PAYMENT_CANCEL_REQUIRES_COMPLETED_PAYMENT를_반환한다() {
        Map<String, Object> account = createAccount(OWNER_ID);
        String accountId = (String) account.get("accountId");
        deposit(accountId, 50000, OWNER_ID);
        Map<String, Object> card = issueCard(OWNER_ID, accountId);
        Map<String, Object> payment =
                createPayment((String) card.get("cardId"), 10000, OWNER_ID).getBody();
        post(
                "/payments/" + payment.get("paymentId") + "/cancel",
                OWNER_ID,
                Map.of("reason", "고객 요청"));

        ResponseEntity<Map> response =
                post(
                        "/payments/" + payment.get("paymentId") + "/cancel",
                        OWNER_ID,
                        Map.of("reason", "고객 요청"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code"))
                .isEqualTo("PAYMENT_CANCEL_REQUIRES_COMPLETED_PAYMENT");
    }

    @Test
    void 환불_금액이_결제_금액을_초과하면_201과_REJECTED_상태를_반환하고_계좌는_크레딧되지_않는다() {
        Map<String, Object> account = createAccount(OWNER_ID);
        String accountId = (String) account.get("accountId");
        deposit(accountId, 50000, OWNER_ID);
        Map<String, Object> card = issueCard(OWNER_ID, accountId);
        Map<String, Object> payment =
                createPayment((String) card.get("cardId"), 10000, OWNER_ID).getBody();
        assertThat(getBalance(accountId, OWNER_ID)).isEqualTo(40000);

        ResponseEntity<Map> response =
                post(
                        "/payments/" + payment.get("paymentId") + "/refunds",
                        OWNER_ID,
                        Map.of("amount", 20000, "reason", "상품 불량"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("status")).isEqualTo("REJECTED");
        assertThat(response.getBody().get("decisionNote")).isEqualTo("환불 금액은 결제 금액을 초과할 수 없습니다.");
        assertThat(getBalance(accountId, OWNER_ID)).isEqualTo(40000);
    }

    @Test
    void 완료되지_않은_취소된_결제에_환불을_요청하면_201과_REJECTED_상태를_반환한다() {
        Map<String, Object> account = createAccount(OWNER_ID);
        String accountId = (String) account.get("accountId");
        deposit(accountId, 50000, OWNER_ID);
        Map<String, Object> card = issueCard(OWNER_ID, accountId);
        Map<String, Object> payment =
                createPayment((String) card.get("cardId"), 10000, OWNER_ID).getBody();
        post(
                "/payments/" + payment.get("paymentId") + "/cancel",
                OWNER_ID,
                Map.of("reason", "고객 요청"));
        assertThat(getBalance(accountId, OWNER_ID)).isEqualTo(50000);

        ResponseEntity<Map> response =
                post(
                        "/payments/" + payment.get("paymentId") + "/refunds",
                        OWNER_ID,
                        Map.of("amount", 5000, "reason", "상품 불량"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("status")).isEqualTo("REJECTED");
        assertThat(response.getBody().get("decisionNote"))
                .isEqualTo("완료된 결제에 대해서만 환불을 요청할 수 있습니다.");
    }

    @Test
    void 유효한_환불_요청이면_201과_APPROVED_상태를_반환하고_계좌가_크레딧된다() {
        Map<String, Object> account = createAccount(OWNER_ID);
        String accountId = (String) account.get("accountId");
        deposit(accountId, 50000, OWNER_ID);
        Map<String, Object> card = issueCard(OWNER_ID, accountId);
        Map<String, Object> payment =
                createPayment((String) card.get("cardId"), 10000, OWNER_ID).getBody();
        assertThat(getBalance(accountId, OWNER_ID)).isEqualTo(40000);

        ResponseEntity<Map> response =
                post(
                        "/payments/" + payment.get("paymentId") + "/refunds",
                        OWNER_ID,
                        Map.of("amount", 4000, "reason", "부분 환불"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("status")).isEqualTo("APPROVED");
        assertThat(getBalance(accountId, OWNER_ID)).isEqualTo(44000);

        Map<String, Object> listBody =
                get("/payments/" + payment.get("paymentId") + "/refunds", OWNER_ID).getBody();
        assertThat(((Number) listBody.get("count")).intValue()).isEqualTo(1);
        var refunds = (java.util.List<Map<String, Object>>) listBody.get("refunds");
        assertThat(refunds.get(0).get("status")).isEqualTo("APPROVED");
    }

    @Test
    void 존재하지_않는_결제에_환불을_요청하면_404를_반환한다() {
        ResponseEntity<Map> response =
                post(
                        "/payments/non-existent/refunds",
                        OWNER_ID,
                        Map.of("amount", 1000, "reason", "사유"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("PAYMENT_NOT_FOUND");
    }

    @Test
    void 내_결제_내역을_페이지네이션과_함께_반환한다() {
        Map<String, Object> account = createAccount(OWNER_ID);
        String accountId = (String) account.get("accountId");
        deposit(accountId, 50000, OWNER_ID);
        Map<String, Object> card = issueCard(OWNER_ID, accountId);
        createPayment((String) card.get("cardId"), 1000, OWNER_ID);
        createPayment((String) card.get("cardId"), 2000, OWNER_ID);

        ResponseEntity<Map> response =
                restTemplate.exchange(
                        "/payments?page=0&take=20",
                        HttpMethod.GET,
                        new HttpEntity<>(headersFor(OWNER_ID)),
                        Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) response.getBody().get("count")).intValue()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void 다른_소유자의_결제를_조회하면_404를_반환한다() {
        Map<String, Object> account = createAccount(OWNER_ID);
        String accountId = (String) account.get("accountId");
        deposit(accountId, 50000, OWNER_ID);
        Map<String, Object> card = issueCard(OWNER_ID, accountId);
        Map<String, Object> payment =
                createPayment((String) card.get("cardId"), 1000, OWNER_ID).getBody();

        ResponseEntity<Map> response = get("/payments/" + payment.get("paymentId"), OTHER_OWNER_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}

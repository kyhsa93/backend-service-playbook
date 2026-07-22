package com.example.accountservice.payment.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.accountservice.AccountServiceApplication;
import com.example.accountservice.support.SqsTestQueue;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * E2E test for the Payment BC. Verifies, through the real HTTP API + Postgres: payment creation,
 * which coordinates the Card and Account BCs through a synchronous Adapter (ACL); the bidirectional
 * Payment↔Account Integration Events (payment.completed.v1 → debit, payment.cancelled.v1/
 * refund.approved.v1 → compensating credit); and RefundEligibilityService (a Domain Service — pure
 * judgment logic coordinating the Payment and Refund Aggregates).
 *
 * <p>The Outbox rows written by {@code CreatePaymentService}/{@code CancelPaymentService}/{@code
 * RequestRefundService} are only reflected on the other side's BC (Account) once {@code
 * OutboxPoller} (1-second cycle) publishes them to SQS and {@code OutboxConsumer} (long polling)
 * receives them — since this is asynchronous, we verify with a polling timeout, the same as {@code
 * CardControllerE2ETest}.
 */
@Testcontainers
@SuppressWarnings("unchecked")
@AutoConfigureTestRestTemplate
@SpringBootTest(
        classes = AccountServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaymentControllerE2ETest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static LocalStackContainer localstack =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.0"))
                    .withServices(LocalStackContainer.Service.SQS);

    // Cached for the same reason as CardControllerE2ETest — the @DynamicPropertySource supplier
    // can be invoked multiple times.
    private static String domainEventQueueUrl;

    private static synchronized String domainEventQueueUrl() {
        if (domainEventQueueUrl == null) {
            domainEventQueueUrl = SqsTestQueue.createDomainEventQueue(localstack);
        }
        return domainEventQueueUrl;
    }

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

        registry.add("aws.region", () -> localstack.getRegion());
        registry.add(
                "aws.endpoint-url",
                () -> localstack.getEndpointOverride(LocalStackContainer.Service.SQS).toString());
        registry.add("aws.access-key-id", () -> localstack.getAccessKey());
        registry.add("aws.secret-access-key", () -> localstack.getSecretKey());
        registry.add("sqs.domain-event-queue-url", PaymentControllerE2ETest::domainEventQueueUrl);
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

    // We poll the card status for the same reason as waitForCardStatus in CardControllerE2ETest
    // (asynchronous processing through Outbox → SQS → OutboxConsumer).
    private void waitForCardStatus(String cardId, String expected, String ownerId) {
        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(
                        () ->
                                assertThat(get("/cards/" + cardId, ownerId).getBody().get("status"))
                                        .isEqualTo(expected));
    }

    // The Account BC's reaction that subscribes to payment.completed.v1/payment.cancelled.v1/
    // refund.approved.v1 and changes the balance is also an asynchronous flow through Outbox →
    // SQS (OutboxPoller/OutboxConsumer), so we poll.
    private long waitForBalance(String accountId, long expected, String ownerId) {
        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(
                        () -> assertThat(getBalance(accountId, ownerId)).isEqualTo(expected));
        return expected;
    }

    private long waitForBalance(String accountId, long expected) {
        return waitForBalance(accountId, expected, OWNER_ID);
    }

    @Test
    void 비활성_카드로_결제하면_400과_PAYMENT_REQUIRES_ACTIVE_CARD를_반환한다() {
        Map<String, Object> account = createAccount(OWNER_ID);
        String accountId = (String) account.get("accountId");
        deposit(accountId, 50000, OWNER_ID);
        Map<String, Object> card = issueCard(OWNER_ID, accountId);
        // There is no card-specific suspend endpoint — suspend the account instead, so the linked
        // card reacts to account.suspended.v1 and transitions to SUSPENDED (poll since it's an
        // asynchronous flow through Outbox → SQS).
        ResponseEntity<Map> suspendResponse =
                post("/accounts/" + accountId + "/suspend", OWNER_ID, Map.of());
        assertThat(suspendResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        waitForCardStatus((String) card.get("cardId"), "SUSPENDED", OWNER_ID);

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

        // The Account BC subscribes to payment.completed.v1 and debits the balance — poll since
        // it's an asynchronous flow through Outbox → SQS.
        waitForBalance(accountId, 40000);
    }

    @Test
    void 완료된_결제를_취소하면_204를_반환하고_계좌_잔액이_복구된다() {
        Map<String, Object> account = createAccount(OWNER_ID);
        String accountId = (String) account.get("accountId");
        deposit(accountId, 50000, OWNER_ID);
        Map<String, Object> card = issueCard(OWNER_ID, accountId);
        Map<String, Object> payment =
                createPayment((String) card.get("cardId"), 10000, OWNER_ID).getBody();
        waitForBalance(accountId, 40000);

        ResponseEntity<Map> cancelResponse =
                post(
                        "/payments/" + payment.get("paymentId") + "/cancel",
                        OWNER_ID,
                        Map.of("reason", "customer request"));

        assertThat(cancelResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        waitForBalance(accountId, 50000);
        assertThat(get("/payments/" + payment.get("paymentId"), OWNER_ID).getBody().get("status"))
                .isEqualTo("CANCELLED");
    }

    @Test
    void 존재하지_않는_결제를_취소하면_404를_반환한다() {
        ResponseEntity<Map> response =
                post("/payments/non-existent/cancel", OWNER_ID, Map.of("reason", "reason"));

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
                Map.of("reason", "customer request"));

        ResponseEntity<Map> response =
                post(
                        "/payments/" + payment.get("paymentId") + "/cancel",
                        OWNER_ID,
                        Map.of("reason", "customer request"));

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
        waitForBalance(accountId, 40000);

        ResponseEntity<Map> response =
                post(
                        "/payments/" + payment.get("paymentId") + "/refunds",
                        OWNER_ID,
                        Map.of("amount", 20000, "reason", "defective product"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("status")).isEqualTo("REJECTED");
        assertThat(response.getBody().get("decisionNote"))
                .isEqualTo("The refund amount cannot exceed the payment amount.");
        // A rejected refund does not publish a Domain Event, so the balance must not change.
        waitForBalance(accountId, 40000);
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
                Map.of("reason", "customer request"));
        waitForBalance(accountId, 50000);

        ResponseEntity<Map> response =
                post(
                        "/payments/" + payment.get("paymentId") + "/refunds",
                        OWNER_ID,
                        Map.of("amount", 5000, "reason", "defective product"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("status")).isEqualTo("REJECTED");
        assertThat(response.getBody().get("decisionNote"))
                .isEqualTo("A refund can only be requested for a completed payment.");
    }

    @Test
    void 유효한_환불_요청이면_201과_APPROVED_상태를_반환하고_계좌가_크레딧된다() {
        Map<String, Object> account = createAccount(OWNER_ID);
        String accountId = (String) account.get("accountId");
        deposit(accountId, 50000, OWNER_ID);
        Map<String, Object> card = issueCard(OWNER_ID, accountId);
        Map<String, Object> payment =
                createPayment((String) card.get("cardId"), 10000, OWNER_ID).getBody();
        waitForBalance(accountId, 40000);

        ResponseEntity<Map> response =
                post(
                        "/payments/" + payment.get("paymentId") + "/refunds",
                        OWNER_ID,
                        Map.of("amount", 4000, "reason", "partial refund"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("status")).isEqualTo("APPROVED");
        waitForBalance(accountId, 44000);

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
                        Map.of("amount", 1000, "reason", "reason"));

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

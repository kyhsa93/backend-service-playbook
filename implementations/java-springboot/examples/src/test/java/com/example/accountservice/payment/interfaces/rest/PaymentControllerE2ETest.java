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

        // OWNER_ID below is shared across every test method in this class, and the Testcontainers
        // Postgres instance persists refund history across them (no per-test reset) — so
        // RefundFraudRiskScorerNativeImpl (the default) would see accumulating refund history
        // across unrelated test methods and could legitimately reject a later test's refund based
        // on an earlier, unrelated test's rejected refunds. Force the http impl against an
        // unreachable address instead, so scoring deterministically falls back to 0 for every
        // call in this suite — the same idiom already used for testRefundReasonClassifier-style
        // determinism, and the same fix applied in the Go port (see
        // RefundFraudRiskScorerHttpImpl.java's fallback-on-failure behavior).
        registry.add("fraud-scorer.mode", () -> "http");
        registry.add("fraud-scorer.base-url", () -> "http://localhost:1");
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
    void returns_400_and_PAYMENT_REQUIRES_ACTIVE_CARD_when_paying_with_an_inactive_card() {
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
    void returns_400_and_INSUFFICIENT_BALANCE_when_balance_is_insufficient() {
        Map<String, Object> account = createAccount(OWNER_ID);
        String accountId = (String) account.get("accountId");
        deposit(accountId, 1000, OWNER_ID);
        Map<String, Object> card = issueCard(OWNER_ID, accountId);

        ResponseEntity<Map> response = createPayment((String) card.get("cardId"), 5000, OWNER_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("INSUFFICIENT_BALANCE");
    }

    @Test
    void returns_404_and_LINKED_CARD_NOT_FOUND_for_a_nonexistent_card() {
        ResponseEntity<Map> response = createPayment("non-existent-card", 1000, OWNER_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("LINKED_CARD_NOT_FOUND");
    }

    @Test
    void returns_404_when_paying_with_another_owners_card() {
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
    void
            returns_201_and_a_COMPLETED_payment_and_debits_the_account_balance_with_an_active_card_and_sufficient_balance() {
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
    void returns_204_and_restores_the_account_balance_when_cancelling_a_completed_payment() {
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
    void returns_404_when_cancelling_a_nonexistent_payment() {
        ResponseEntity<Map> response =
                post("/payments/non-existent/cancel", OWNER_ID, Map.of("reason", "reason"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("PAYMENT_NOT_FOUND");
    }

    @Test
    void
            returns_400_and_PAYMENT_CANCEL_REQUIRES_COMPLETED_PAYMENT_when_cancelling_an_already_cancelled_payment_again() {
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
    void
            returns_201_and_REJECTED_status_without_crediting_the_account_when_refund_amount_exceeds_the_payment_amount() {
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
    void
            returns_201_and_REJECTED_status_when_requesting_a_refund_on_a_cancelled_not_completed_payment() {
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
    void returns_201_and_APPROVED_status_and_credits_the_account_for_a_valid_refund_request() {
        // A dedicated owner id (not the shared OWNER_ID) — RefundFraudRiskScorer's native
        // implementation weighs this owner's actual refund history (see
        // RefundRepository.summarizeRefundsByOwner), and OWNER_ID accumulates refunds/rejections
        // across the other tests in this class (no per-test cleanup). Reusing it here would make
        // this test's ML fraud-risk score depend on execution order/what ran before it.
        String ownerId = "payment-owner-valid-refund";
        Map<String, Object> account = createAccount(ownerId);
        String accountId = (String) account.get("accountId");
        deposit(accountId, 50000, ownerId);
        Map<String, Object> card = issueCard(ownerId, accountId);
        Map<String, Object> payment =
                createPayment((String) card.get("cardId"), 10000, ownerId).getBody();
        waitForBalance(accountId, 40000, ownerId);

        ResponseEntity<Map> response =
                post(
                        "/payments/" + payment.get("paymentId") + "/refunds",
                        ownerId,
                        Map.of("amount", 4000, "reason", "partial refund"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("status")).isEqualTo("APPROVED");
        waitForBalance(accountId, 44000, ownerId);

        Map<String, Object> listBody =
                get("/payments/" + payment.get("paymentId") + "/refunds", ownerId).getBody();
        assertThat(((Number) listBody.get("count")).intValue()).isEqualTo(1);
        var refunds = (java.util.List<Map<String, Object>>) listBody.get("refunds");
        assertThat(refunds.get(0).get("status")).isEqualTo("APPROVED");
    }

    @Test
    void returns_404_when_requesting_a_refund_on_a_nonexistent_payment() {
        ResponseEntity<Map> response =
                post(
                        "/payments/non-existent/refunds",
                        OWNER_ID,
                        Map.of("amount", 1000, "reason", "reason"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("PAYMENT_NOT_FOUND");
    }

    @Test
    void returns_my_payment_history_with_pagination() {
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
    void returns_404_when_fetching_another_owners_payment() {
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

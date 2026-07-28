package com.example.accountservice.payment.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.accountservice.AccountServiceApplication;
import com.example.accountservice.support.SqsTestQueue;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
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
 * Exercises the real async pipeline end to end: refund request → {@code RefundRequestedEvent} →
 * Outbox → SQS → {@code OutboxConsumer} → {@code OutboxEventDispatcher} → {@code
 * ClassifyRefundReasonEventHandler} → the {@code RefundRepository} write, and {@code GET
 * /refunds/reason-insights}. The LLM behind {@code RefundReasonClassifier} isn't available in this
 * e2e environment (same reasoning as {@code TransactionCategorizationE2ETest}), so the
 * classification call itself falls back to {@code OTHER} — but this still proves the whole plumbing
 * runs, and — the key design point of this feature — that classification runs identically for a
 * {@code REJECTED} refund, since {@code RefundRequestedEvent} is published by {@link
 * com.example.accountservice.payment.domain.Refund#create} before {@code
 * RefundEligibilityService}'s approve/reject judgment even runs.
 */
@Testcontainers
@SuppressWarnings("unchecked")
@AutoConfigureTestRestTemplate
@SpringBootTest(
        classes = AccountServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RefundReasonInsightsE2ETest {

    private static final String OWNER_ID = "refund-insights-owner-1";
    private static final String PASSWORD = "password123!";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static LocalStackContainer localstack =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.0"))
                    .withServices(LocalStackContainer.Service.SQS);

    // Cached for the same reason as PaymentControllerE2ETest — the @DynamicPropertySource
    // supplier can be invoked multiple times.
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
        registry.add(
                "sqs.domain-event-queue-url", RefundReasonInsightsE2ETest::domainEventQueueUrl);
    }

    @Autowired private TestRestTemplate restTemplate;

    private String tokenFor(String userId) {
        restTemplate.postForEntity(
                "/auth/sign-up", Map.of("userId", userId, "password", PASSWORD), Map.class);
        ResponseEntity<Map> response =
                restTemplate.postForEntity(
                        "/auth/sign-in", Map.of("userId", userId, "password", PASSWORD), Map.class);
        return (String) response.getBody().get("accessToken");
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
        return post(
                        "/accounts",
                        ownerId,
                        Map.of("currency", "KRW", "email", ownerId + "@example.com"))
                .getBody();
    }

    private void deposit(String accountId, long amount, String ownerId) {
        post("/accounts/" + accountId + "/deposit", ownerId, Map.of("amount", amount));
    }

    private Map<String, Object> issueCard(String ownerId, String accountId) {
        return post("/cards", ownerId, Map.of("accountId", accountId, "brand", "VISA")).getBody();
    }

    private Map<String, Object> createPayment(String cardId, long amount, String ownerId) {
        return post("/payments", ownerId, Map.of("cardId", cardId, "amount", amount)).getBody();
    }

    private long getBalance(String accountId, String ownerId) {
        Map<String, Object> balance =
                (Map<String, Object>)
                        get("/accounts/" + accountId, ownerId).getBody().get("balance");
        return ((Number) balance.get("amount")).longValue();
    }

    // The Account BC's reaction to payment.completed.v1 that debits the balance is asynchronous
    // (Outbox → SQS), so we poll — the same reason as PaymentControllerE2ETest's waitForBalance.
    private void waitForBalance(String accountId, long expected, String ownerId) {
        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(
                        () -> assertThat(getBalance(accountId, ownerId)).isEqualTo(expected));
    }

    private Map<String, Object> requestRefund(
            String paymentId, long amount, String reason, String ownerId) {
        return post(
                        "/payments/" + paymentId + "/refunds",
                        ownerId,
                        Map.of("amount", amount, "reason", reason))
                .getBody();
    }

    private Map<String, Object> firstRefund(String paymentId, String ownerId) {
        Map<String, Object> body = get("/payments/" + paymentId + "/refunds", ownerId).getBody();
        List<Map<String, Object>> refunds = (List<Map<String, Object>>) body.get("refunds");
        return refunds.isEmpty() ? null : refunds.get(0);
    }

    private long totalClassified(String ownerId) {
        return ((Number) get("/refunds/reason-insights", ownerId).getBody().get("totalClassified"))
                .longValue();
    }

    @Test
    void a_rejected_refund_still_gets_its_reason_classified_asynchronously() {
        Map<String, Object> account = createAccount(OWNER_ID);
        String accountId = (String) account.get("accountId");
        deposit(accountId, 50000, OWNER_ID);
        Map<String, Object> card = issueCard(OWNER_ID, accountId);
        Map<String, Object> payment = createPayment((String) card.get("cardId"), 10000, OWNER_ID);
        waitForBalance(accountId, 40000, OWNER_ID);

        // A refund amount exceeding the payment amount is REJECTED by RefundEligibilityService —
        // but RefundRequestedEvent is still published unconditionally by Refund.create(), before
        // that judgment even runs.
        Map<String, Object> refund =
                requestRefund(
                        (String) payment.get("paymentId"),
                        20000,
                        "The item arrived broken",
                        OWNER_ID);
        assertThat(refund.get("status")).isEqualTo("REJECTED");

        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(
                        () -> {
                            Map<String, Object> listed =
                                    firstRefund((String) payment.get("paymentId"), OWNER_ID);
                            assertThat(listed).isNotNull();
                            assertThat(listed.get("reasonCategory")).isEqualTo("OTHER");
                        });
    }

    @Test
    void get_refunds_reason_insights_reflects_a_classified_refund_in_its_category_counts() {
        long totalBefore = totalClassified(OWNER_ID);

        Map<String, Object> account = createAccount(OWNER_ID);
        String accountId = (String) account.get("accountId");
        deposit(accountId, 50000, OWNER_ID);
        Map<String, Object> card = issueCard(OWNER_ID, accountId);
        Map<String, Object> payment = createPayment((String) card.get("cardId"), 10000, OWNER_ID);
        waitForBalance(accountId, 40000, OWNER_ID);
        requestRefund((String) payment.get("paymentId"), 4000, "Changed my mind", OWNER_ID);

        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(
                        () -> assertThat(totalClassified(OWNER_ID)).isGreaterThan(totalBefore));
    }
}

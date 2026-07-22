package com.example.accountservice.account.infrastructure.scheduling;

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
 * E2E test verifying the actual infrastructure round trip for the periodic interest payment
 * (scheduling.md Feature 1). Instead of waiting for a real Cron tick (3 AM), it calls {@link
 * InterestPaymentScheduler#enqueueDailyInterestPayment()} directly, exercising the full path:
 * Scheduler → task_outbox → TaskOutboxPoller → Task Queue (SQS FIFO, LocalStack) → TaskConsumer →
 * PayInterestTaskController → PayInterestService → Account.payInterest().
 */
@Testcontainers
@SuppressWarnings("unchecked")
@AutoConfigureTestRestTemplate
@SpringBootTest(
        classes = AccountServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InterestPaymentSchedulingE2ETest {

    private static final String OWNER_ID = "interest-owner-1";
    private static final String PASSWORD = "password123!";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static LocalStackContainer localstack =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.0"))
                    .withServices(LocalStackContainer.Service.SQS);

    // The @DynamicPropertySource supplier can be invoked multiple times (same reason as
    // CardControllerE2ETest), so the queue is created only once and cached.
    private static String domainEventQueueUrl;
    private static String taskQueueUrl;

    private static synchronized String domainEventQueueUrl() {
        if (domainEventQueueUrl == null) {
            domainEventQueueUrl = SqsTestQueue.createDomainEventQueue(localstack);
        }
        return domainEventQueueUrl;
    }

    private static synchronized String taskQueueUrl() {
        if (taskQueueUrl == null) {
            taskQueueUrl = SqsTestQueue.createTaskQueue(localstack);
        }
        return taskQueueUrl;
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
                "sqs.domain-event-queue-url",
                InterestPaymentSchedulingE2ETest::domainEventQueueUrl);
        registry.add("sqs.task-queue-url", InterestPaymentSchedulingE2ETest::taskQueueUrl);
    }

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private InterestPaymentScheduler interestPaymentScheduler;

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

    private String createAccountWithBalance(String ownerId, long amount) {
        ResponseEntity<Map> created =
                post(
                        "/accounts",
                        ownerId,
                        Map.of("currency", "KRW", "email", ownerId + "@example.com"));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String accountId = (String) created.getBody().get("accountId");

        ResponseEntity<Map> deposited =
                post("/accounts/" + accountId + "/deposit", ownerId, Map.of("amount", amount));
        assertThat(deposited.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return accountId;
    }

    private long balanceOf(String accountId, String ownerId) {
        Map<String, Object> balance =
                (Map<String, Object>)
                        get("/accounts/" + accountId, ownerId).getBody().get("balance");
        return ((Number) balance.get("amount")).longValue();
    }

    // From the moment Scheduler.enqueueDailyInterestPayment() is called until the balance is
    // actually updated, the request has to travel the full path: task_outbox →
    // TaskOutboxPoller (1-second cycle) → SQS (LocalStack) → TaskConsumer (long polling) → Task
    // Controller → PayInterestService, so it is not reflected immediately — we poll for the same
    // reason as waitForCardStatus in CardControllerE2ETest.
    private void waitForBalance(String accountId, String ownerId, long expected) {
        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(balanceOf(accountId, ownerId)).isEqualTo(expected));
    }

    // Both scenarios (end-to-end propagation + idempotency) are verified in a single test against
    // the same account and date — if they were split into separate test methods, both would
    // enqueue the same taskType (account.pay-interest) for "today", and the FIFO queue's 5-minute
    // dedup window could suppress the enqueue of a second test targeting a different account. We
    // avoid that race entirely by enqueuing twice against the same account.
    @Test
    void
            enqueueing_the_interest_payment_batch_updates_balance_and_re_enqueueing_the_same_day_applies_only_once() {
        String accountId = createAccountWithBalance(OWNER_ID, 1_000_000);

        interestPaymentScheduler.enqueueDailyInterestPayment();

        waitForBalance(accountId, OWNER_ID, 1_000_100); // 1_000_000 * 0.0001

        List<Map<String, Object>> transactions =
                (List<Map<String, Object>>)
                        get("/accounts/" + accountId + "/transactions", OWNER_ID)
                                .getBody()
                                .get("transactions");
        assertThat(transactions)
                .anySatisfy(
                        t -> {
                            assertThat(t.get("type")).isEqualTo("INTEREST");
                            Map<String, Object> amount = (Map<String, Object>) t.get("amount");
                            assertThat(((Number) amount.get("amount")).longValue()).isEqualTo(100);
                        });

        // Enqueue a second time on the same day — confirms that Account.payInterest()'s Level 1
        // idempotency (lastInterestPaidAt) actually makes the second processing a no-op (whether
        // FIFO dedup blocks the second publish outright, or it fails to and TaskConsumer processes
        // it again, the result must be the same).
        interestPaymentScheduler.enqueueDailyInterestPayment();

        await().pollDelay(Duration.ofSeconds(3))
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () -> assertThat(balanceOf(accountId, OWNER_ID)).isEqualTo(1_000_100));
    }
}

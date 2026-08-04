package com.example.accountservice.account.infrastructure.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.accountservice.AccountServiceApplication;
import com.example.accountservice.account.infrastructure.PreviousSpendingAnalysisPeriod;
import com.example.accountservice.common.UtcClock;
import com.example.accountservice.support.SqsTestQueue;
import java.time.Duration;
import java.time.LocalDateTime;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * E2E test verifying the actual infrastructure round trip for the monthly spending-analysis ETL.
 * Instead of waiting for a real Cron tick (2 AM on the 1st), it calls {@link
 * SpendingAnalysisScheduler#enqueueMonthlySpendingAnalysis()} directly, exercising the full path:
 * Scheduler → task_outbox → TaskOutboxPoller → Task Queue (SQS FIFO, LocalStack) → TaskConsumer →
 * AnalyzeMonthlySpendingTaskController → AnalyzeMonthlySpendingService →
 * SpendingAnalysisRepository. Mirrors {@code InterestPaymentSchedulingE2ETest}'s structure.
 */
@Testcontainers
@SuppressWarnings("unchecked")
@AutoConfigureTestRestTemplate
@SpringBootTest(
        classes = AccountServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SpendingAnalysisSchedulingE2ETest {

    private static final String OWNER_ID = "spending-analysis-owner-1";
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
                SpendingAnalysisSchedulingE2ETest::domainEventQueueUrl);
        registry.add("sqs.task-queue-url", SpendingAnalysisSchedulingE2ETest::taskQueueUrl);
    }

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private SpendingAnalysisScheduler spendingAnalysisScheduler;
    @Autowired private JdbcTemplate jdbcTemplate;

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

    private String createAccount(String ownerId) {
        ResponseEntity<Map> created =
                post(
                        "/accounts",
                        ownerId,
                        Map.of("currency", "KRW", "email", ownerId + "@example.com"));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) created.getBody().get("accountId");
    }

    private String createAccountWithBalance(String ownerId, long amount) {
        String accountId = createAccount(ownerId);
        ResponseEntity<Map> deposited =
                post("/accounts/" + accountId + "/deposit", ownerId, Map.of("amount", amount));
        assertThat(deposited.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return accountId;
    }

    private String withdraw(String accountId, String ownerId, long amount) {
        ResponseEntity<Map> withdrawn =
                post("/accounts/" + accountId + "/withdraw", ownerId, Map.of("amount", amount));
        assertThat(withdrawn.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) withdrawn.getBody().get("transactionId");
    }

    // Backdates a withdrawal's createdAt into "last month" via a direct SQL update, the same
    // approach as nestjs's e2e test backdating via the TypeORM repository directly — the
    // AnalyzeMonthlySpendingService reads createdAt to bucket a transaction into the current vs.
    // previous analysis month, so the fixture must move the row's actual createdAt, not just its
    // reported time.
    private void backdateTransaction(String transactionId, LocalDateTime backdatedAt) {
        jdbcTemplate.update(
                "UPDATE transactions SET created_at = ? WHERE transaction_id = ?",
                backdatedAt,
                transactionId);
    }

    private Map<String, Object> findAnalysisRow(String accountId, String analysisMonth) {
        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList(
                        "SELECT * FROM spending_analysis WHERE account_id = ? AND analysis_month = ?",
                        accountId,
                        analysisMonth);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private long countAnalysisRows(String accountId, String analysisMonth) {
        Long count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM spending_analysis WHERE account_id = ? AND analysis_month = ?",
                        Long.class,
                        accountId,
                        analysisMonth);
        return count == null ? 0 : count;
    }

    @Test
    void
            an_account_with_last_months_withdrawal_history_gets_an_analysis_row_queryable_via_the_api_and_re_enqueueing_in_the_same_month_does_not_duplicate_it() {
        String accountId = createAccountWithBalance(OWNER_ID, 1_000_000);

        // Backdates the withdrawals into "last month" the same way the nestjs e2e test does —
        // reusing the scheduler's own period computation so the analysis only lines up if it
        // matches the logic the Scheduler actually runs with.
        PreviousSpendingAnalysisPeriod period =
                PreviousSpendingAnalysisPeriod.compute(UtcClock.currentMonth());
        LocalDateTime backdatedAt = period.monthStart().plusDays(1);
        String transactionId1 = withdraw(accountId, OWNER_ID, 30000);
        String transactionId2 = withdraw(accountId, OWNER_ID, 20000);
        backdateTransaction(transactionId1, backdatedAt);
        backdateTransaction(transactionId2, backdatedAt);

        spendingAnalysisScheduler.enqueueMonthlySpendingAnalysis();

        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(
                        () ->
                                assertThat(findAnalysisRow(accountId, period.analysisMonth()))
                                        .isNotNull());

        Map<String, Object> row = findAnalysisRow(accountId, period.analysisMonth());
        assertThat(((Number) row.get("total_amount")).longValue()).isEqualTo(50000);
        assertThat(((Number) row.get("transaction_count")).longValue()).isEqualTo(2);
        assertThat(((Number) row.get("average_amount")).longValue()).isEqualTo(25000);
        // No prior-prior month withdrawal history exists, so the comparison baseline is 0 — the
        // %-change is capped at 100 and the trend is INCREASING (see SpendingAnalysis.create).
        assertThat(((Number) row.get("change_from_previous_month")).intValue()).isEqualTo(100);
        assertThat(row.get("trend")).isEqualTo("INCREASING");

        ResponseEntity<Map> response =
                restTemplate.exchange(
                        "/accounts/"
                                + accountId
                                + "/spending-analysis?month="
                                + period.analysisMonth()
                                + "-01",
                        HttpMethod.GET,
                        new HttpEntity<>(headersFor(OWNER_ID)),
                        Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) response.getBody().get("totalAmount")).longValue()).isEqualTo(50000);
        assertThat(response.getBody().get("trend")).isEqualTo("INCREASING");

        // Since it's the same month's dedupId, even if the second enqueue is reprocessed, the
        // (accountId, analysisMonth) unique constraint + the hasAnalysis precheck must prevent a
        // duplicate row.
        spendingAnalysisScheduler.enqueueMonthlySpendingAnalysis();
        await().pollDelay(Duration.ofSeconds(3))
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () ->
                                assertThat(countAnalysisRows(accountId, period.analysisMonth()))
                                        .isEqualTo(1));
    }

    @Test
    void when_no_analysis_has_been_computed_for_the_requested_month_then_returns_404() {
        String accountId = createAccount(OWNER_ID + "-2");

        ResponseEntity<Map> response =
                restTemplate.exchange(
                        "/accounts/" + accountId + "/spending-analysis?month=2020-01-01",
                        HttpMethod.GET,
                        new HttpEntity<>(headersFor(OWNER_ID + "-2")),
                        Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("SPENDING_ANALYSIS_NOT_FOUND");
    }
}

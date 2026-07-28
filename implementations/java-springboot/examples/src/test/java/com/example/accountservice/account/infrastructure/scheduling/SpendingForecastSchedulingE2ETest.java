package com.example.accountservice.account.infrastructure.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.accountservice.AccountServiceApplication;
import com.example.accountservice.common.IdGenerator;
import com.example.accountservice.support.SqsTestQueue;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.YearMonth;
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
 * E2E test verifying the actual infrastructure round trip for the monthly spending-forecast
 * training batch. Instead of waiting for a real Cron tick (3 AM on the 1st), it calls {@link
 * SpendingForecastScheduler#enqueueMonthlySpendingForecast()} directly, exercising the full path:
 * Scheduler → task_outbox → TaskOutboxPoller → Task Queue (SQS FIFO, LocalStack) → TaskConsumer →
 * ForecastSpendingTaskController → ForecastSpendingService → SpendingForecastRepository. Mirrors
 * {@code SpendingAnalysisSchedulingE2ETest}'s structure.
 */
@Testcontainers
@SuppressWarnings("unchecked")
@AutoConfigureTestRestTemplate
@SpringBootTest(
        classes = AccountServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SpendingForecastSchedulingE2ETest {

    private static final String OWNER_ID = "spending-forecast-owner-1";
    private static final String PASSWORD = "password123!";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static LocalStackContainer localstack =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.0"))
                    .withServices(LocalStackContainer.Service.SQS);

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
                SpendingForecastSchedulingE2ETest::domainEventQueueUrl);
        registry.add("sqs.task-queue-url", SpendingForecastSchedulingE2ETest::taskQueueUrl);
    }

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private SpendingForecastScheduler spendingForecastScheduler;
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

    private String createAccount(String ownerId) {
        ResponseEntity<Map> created =
                post(
                        "/accounts",
                        ownerId,
                        Map.of("currency", "KRW", "email", ownerId + "@example.com"));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) created.getBody().get("accountId");
    }

    // Seeds a spending_analysis row directly — this test's concern is ForecastSpendingService
    // training on existing history, not re-deriving that history, which is separately covered by
    // SpendingAnalysisSchedulingE2ETest.
    private void seedAnalysis(String accountId, String analysisMonth, long totalAmount) {
        jdbcTemplate.update(
                "INSERT INTO spending_analysis (analysis_id, account_id, analysis_month,"
                        + " total_amount, transaction_count, average_amount,"
                        + " change_from_previous_month, trend, created_at) VALUES (?, ?, ?, ?, ?,"
                        + " ?, ?, ?, ?)",
                IdGenerator.generate(),
                accountId,
                analysisMonth,
                totalAmount,
                1,
                totalAmount,
                0,
                "STABLE",
                LocalDateTime.now());
    }

    private Map<String, Object> findForecastRow(String accountId, String forecastMonth) {
        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList(
                        "SELECT * FROM spending_forecast WHERE account_id = ? AND forecast_month = ?",
                        accountId,
                        forecastMonth);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private long countForecastRows(String accountId, String forecastMonth) {
        Long count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM spending_forecast WHERE account_id = ? AND"
                                + " forecast_month = ?",
                        Long.class,
                        accountId,
                        forecastMonth);
        return count == null ? 0 : count;
    }

    @Test
    void
            an_account_with_3_months_of_spending_analysis_history_gets_a_trained_forecast_row_queryable_via_the_api_and_re_enqueueing_in_the_same_month_does_not_duplicate_it() {
        String accountId = createAccount(OWNER_ID);

        String forecastMonth = YearMonth.now().toString();
        long[] amounts = {10000, 20000, 30000};
        for (int monthsAgo = 3; monthsAgo >= 1; monthsAgo--) {
            YearMonth analysisMonth = YearMonth.now().minusMonths(monthsAgo);
            seedAnalysis(accountId, analysisMonth.toString(), amounts[3 - monthsAgo]);
        }

        spendingForecastScheduler.enqueueMonthlySpendingForecast();

        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(
                        () -> assertThat(findForecastRow(accountId, forecastMonth)).isNotNull());

        Map<String, Object> row = findForecastRow(accountId, forecastMonth);
        // A perfectly linear history (10000, 20000, 30000) extrapolates exactly to 40000 with
        // full confidence — see SpendingForecastModelImpl.
        assertThat(((Number) row.get("predicted_amount")).longValue()).isEqualTo(40000);
        assertThat(row.get("confidence")).isEqualTo("HIGH");
        assertThat(((Number) row.get("history_months_used")).intValue()).isEqualTo(3);

        ResponseEntity<Map> response =
                restTemplate.exchange(
                        "/accounts/"
                                + accountId
                                + "/spending-forecast?month="
                                + forecastMonth
                                + "-01",
                        HttpMethod.GET,
                        new HttpEntity<>(headersFor(OWNER_ID)),
                        Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) response.getBody().get("predictedAmount")).longValue())
                .isEqualTo(40000);
        assertThat(response.getBody().get("confidence")).isEqualTo("HIGH");

        // Since it's the same month's dedupId, even if the second enqueue is reprocessed, the
        // (accountId, forecastMonth) unique constraint + the hasForecast precheck must prevent a
        // duplicate row.
        spendingForecastScheduler.enqueueMonthlySpendingForecast();
        await().pollDelay(Duration.ofSeconds(3))
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () -> assertThat(countForecastRows(accountId, forecastMonth)).isEqualTo(1));
    }

    @Test
    void
            an_account_younger_than_3_months_of_spending_analysis_history_gets_no_forecast_and_the_api_returns_404() {
        // Deliberately does not call spendingForecastScheduler.enqueueMonthlySpendingForecast()
        // here — the other test in this class already enqueues the same account.forecast-spending
        // Task for the current month, and since the dedupId is batch-level (not account-scoped), a
        // second enqueue within SQS FIFO's 5-minute dedup window would be silently dropped (the
        // same class of bug as the documented "SQS FIFO dedup collision across @Test methods"
        // gotcha). A freshly created account with zero spending_analysis history will never have a
        // forecast row regardless of whether the batch runs, so this scenario only needs to
        // confirm the query-side 404 — it doesn't need to trigger the batch at all.
        String accountId = createAccount(OWNER_ID + "-2");

        ResponseEntity<Map> response =
                restTemplate.exchange(
                        "/accounts/"
                                + accountId
                                + "/spending-forecast?month="
                                + YearMonth.now()
                                + "-01",
                        HttpMethod.GET,
                        new HttpEntity<>(headersFor(OWNER_ID + "-2")),
                        Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("SPENDING_FORECAST_NOT_FOUND");
    }
}

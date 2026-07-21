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
 * 정기 이자 지급(scheduling.md Feature 1)의 실제 인프라 왕복을 검증하는 E2E 테스트. 진짜 Cron tick(새벽 3시)을 기다리는 대신 {@link
 * InterestPaymentScheduler#enqueueDailyInterestPayment()}를 직접 호출해, Scheduler → task_outbox →
 * TaskOutboxPoller → Task Queue(SQS FIFO, LocalStack) → TaskConsumer → PayInterestTaskController →
 * PayInterestService → Account.payInterest()로 이어지는 전체 경로를 실제로 태운다.
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

    // @DynamicPropertySource의 Supplier는 여러 번 호출될 수 있으므로(CardControllerE2ETest와 동일한
    // 이유) 큐 생성은 한 번만 하고 캐시한다.
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

    // Scheduler.enqueueDailyInterestPayment() 호출 시점부터 실제 잔액 반영까지는 task_outbox →
    // TaskOutboxPoller(1초 주기) → SQS(LocalStack) → TaskConsumer(long polling) → Task Controller →
    // PayInterestService 전체 경로를 거쳐야 하므로 즉시 반영되지 않는다 — CardControllerE2ETest의
    // waitForCardStatus와 동일한 이유로 폴링한다.
    private void waitForBalance(String accountId, String ownerId, long expected) {
        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(balanceOf(accountId, ownerId)).isEqualTo(expected));
    }

    // 두 시나리오(전체 경로 반영 + 멱등성)를 한 테스트 안에서 같은 계좌·같은 날짜로 검증한다 — 별도
    // 테스트 메서드로 나누면 둘 다 "오늘" 날짜의 같은 taskType(account.pay-interest)을 적재하게 되어
    // FIFO 큐의 5분 dedup 윈도우가 서로 다른 계좌를 대상으로 한 두 번째 테스트의 적재를 억제해버릴 수
    // 있다 — 같은 계좌를 대상으로 두 번 적재해 이 레이스를 근본적으로 피한다.
    @Test
    void 이자_지급_배치를_적재하면_잔액에_반영되고_같은_날_다시_적재해도_한번만_반영된다() {
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

        // 같은 날 두 번째로 적재 — Account.payInterest()의 Level 1 멱등성(lastInterestPaidAt)이 실제로
        // 두 번째 처리를 no-op으로 만드는지 확인한다(FIFO dedup이 두 번째 발행 자체를 막든, 막지 못해
        // TaskConsumer가 다시 처리하든 결과는 동일해야 한다).
        interestPaymentScheduler.enqueueDailyInterestPayment();

        await().pollDelay(Duration.ofSeconds(3))
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () -> assertThat(balanceOf(accountId, OWNER_ID)).isEqualTo(1_000_100));
    }
}

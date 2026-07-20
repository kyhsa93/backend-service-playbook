package com.example.accountservice.card.interfaces.rest;

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
 * Card BC의 E2E 테스트. 두 크로스 도메인 흐름을 실제 HTTP API로 검증한다.
 *
 * <ol>
 *   <li>동기 흐름: 카드 발급 시 {@code AccountAdapter}를 통해 실제 Account BC를 조회해 활성/비활성/존재하지 않는 계좌 경로를 모두 확인한다.
 *   <li>비동기 흐름: Account를 정지/해지하는 실제 HTTP 요청이 Outbox → SQS(LocalStack)를 거쳐 Card BC의 {@code
 *       OutboxEventHandler}로 라우팅되어 연결된 카드의 상태가 바뀌는지 확인한다. {@code OutboxPoller}(1초 주기)가 이벤트를 SQS로
 *       발행하고 {@code OutboxConsumer}(long polling)가 수신해야 처리되므로, HTTP 응답 시점에는 아직 카드 상태가 바뀌지 않았을 수 있다
 *       — {@code waitForCardStatus}로 폴링해서 검증한다.
 * </ol>
 */
@Testcontainers
@SuppressWarnings("unchecked")
@AutoConfigureTestRestTemplate
@SpringBootTest(
        classes = AccountServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CardControllerE2ETest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static LocalStackContainer localstack =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.0"))
                    .withServices(LocalStackContainer.Service.SQS);

    // @DynamicPropertySource의 Supplier는 프로퍼티가 조회될 때마다(여러 번일 수 있다) 호출될 수
    // 있으므로, 큐 생성은 한 번만 수행하고 결과를 캐시해 재호출 시 재사용한다.
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
        // 테스트는 짧은 시간 안에 write API를 기본 limit-for-period(10)보다 훨씬 많이 호출하므로
        // rate limiting 자체가 아니라 각 엔드포인트 로직을 검증할 수 있도록 테스트 한정으로 넉넉하게 푼다.
        registry.add(
                "resilience4j.ratelimiter.instances.http-write.limit-for-period", () -> "1000");

        registry.add("aws.region", () -> localstack.getRegion());
        registry.add(
                "aws.endpoint-url",
                () -> localstack.getEndpointOverride(LocalStackContainer.Service.SQS).toString());
        registry.add("aws.access-key-id", () -> localstack.getAccessKey());
        registry.add("aws.secret-access-key", () -> localstack.getSecretKey());
        registry.add("sqs.domain-event-queue-url", CardControllerE2ETest::domainEventQueueUrl);
    }

    @Autowired private TestRestTemplate restTemplate;

    private static final String OWNER_ID = "card-owner-1";
    private static final String OTHER_OWNER_ID = "card-owner-2";
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

    private Map<String, Object> issueCard(String ownerId, String accountId) {
        ResponseEntity<Map> response =
                post("/cards", ownerId, Map.of("accountId", accountId, "brand", "VISA"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    // Account를 정지/종료하면 AccountSuspended/AccountClosed(Domain Event) → account.suspended.v1/
    // account.closed.v1(Integration Event)이 Outbox에 적재되고, OutboxPoller(1초 주기)가 SQS로
    // 발행한 뒤 OutboxConsumer(long polling)가 수신해 Card BC의 OutboxEventHandler를 실행해야
    // 카드 상태가 바뀐다 — HTTP 응답 시점에는 아직 반영되지 않았을 수 있으므로 폴링한다. 실제
    // SQS(LocalStack) 왕복 지연(폴링 주기 1초 + long poll 대기)을 감안해 넉넉한 타임아웃을 둔다.
    // untilAsserted는 타임아웃 시 마지막 AssertionError(실제 vs 기대값)를 그대로 노출해준다.
    private void waitForCardStatus(String cardId, String expected, String ownerId) {
        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(
                        () ->
                                assertThat(get("/cards/" + cardId, ownerId).getBody().get("status"))
                                        .isEqualTo(expected));
    }

    private void waitForCardStatus(String cardId, String expected) {
        waitForCardStatus(cardId, expected, OWNER_ID);
    }

    @Test
    void 활성_계좌로_카드를_발급하면_201과_ACTIVE_카드를_반환한다() {
        Map<String, Object> account = createAccount(OWNER_ID);

        ResponseEntity<Map> response =
                post(
                        "/cards",
                        OWNER_ID,
                        Map.of("accountId", account.get("accountId"), "brand", "VISA"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> body = response.getBody();
        assertThat(body.get("accountId")).isEqualTo(account.get("accountId"));
        assertThat(body.get("ownerId")).isEqualTo(OWNER_ID);
        assertThat(body.get("brand")).isEqualTo("VISA");
        assertThat(body.get("status")).isEqualTo("ACTIVE");
        assertThat(body.get("cardId")).isNotNull();
    }

    @Test
    void 존재하지_않는_계좌로_카드를_발급하면_404와_LINKED_ACCOUNT_NOT_FOUND를_반환한다() {
        ResponseEntity<Map> response =
                post("/cards", OWNER_ID, Map.of("accountId", "non-existent", "brand", "VISA"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("LINKED_ACCOUNT_NOT_FOUND");
    }

    @Test
    void 다른_소유자의_계좌로_카드를_발급하면_404와_LINKED_ACCOUNT_NOT_FOUND를_반환한다() {
        Map<String, Object> account = createAccount(OWNER_ID);

        ResponseEntity<Map> response =
                post(
                        "/cards",
                        OTHER_OWNER_ID,
                        Map.of("accountId", account.get("accountId"), "brand", "VISA"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("LINKED_ACCOUNT_NOT_FOUND");
    }

    @Test
    void 정지된_계좌로_카드를_발급하면_400과_CARD_ISSUE_REQUIRES_ACTIVE_ACCOUNT를_반환한다() {
        Map<String, Object> account = createAccount(OWNER_ID);
        post("/accounts/" + account.get("accountId") + "/suspend", OWNER_ID, Map.of());

        ResponseEntity<Map> response =
                post(
                        "/cards",
                        OWNER_ID,
                        Map.of("accountId", account.get("accountId"), "brand", "VISA"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("CARD_ISSUE_REQUIRES_ACTIVE_ACCOUNT");
    }

    @Test
    void 발급한_카드를_조회하면_200과_카드_정보를_반환한다() {
        Map<String, Object> account = createAccount(OWNER_ID);
        Map<String, Object> card = issueCard(OWNER_ID, (String) account.get("accountId"));

        ResponseEntity<Map> response = get("/cards/" + card.get("cardId"), OWNER_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("cardId")).isEqualTo(card.get("cardId"));
    }

    @Test
    void 존재하지_않는_카드를_조회하면_404와_CARD_NOT_FOUND를_반환한다() {
        ResponseEntity<Map> response = get("/cards/non-existent", OWNER_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("CARD_NOT_FOUND");
    }

    @Test
    void 계좌를_정지하면_연결된_ACTIVE_카드가_전부_SUSPENDED로_바뀐다() {
        Map<String, Object> account = createAccount(OWNER_ID);
        String accountId = (String) account.get("accountId");
        Map<String, Object> card1 = issueCard(OWNER_ID, accountId);
        Map<String, Object> card2 = issueCard(OWNER_ID, accountId);

        ResponseEntity<Map> suspendResponse =
                post("/accounts/" + accountId + "/suspend", OWNER_ID, Map.of());
        assertThat(suspendResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // OutboxPoller(1초 주기)가 AccountSuspended(Domain Event) → account.suspended.v1
        // (Integration Event)을 SQS로 발행하고, OutboxConsumer(long polling)가 수신해 Card BC의
        // OutboxEventHandler를 실행해야 카드 상태가 바뀐다 — 이 흐름은 비동기이므로 응답을 받은
        // 시점에 아직 반영되지 않았을 수 있다. 폴링해서 검증한다.
        waitForCardStatus((String) card1.get("cardId"), "SUSPENDED");
        waitForCardStatus((String) card2.get("cardId"), "SUSPENDED");
    }

    @Test
    void 이미_정지된_카드가_있는_계좌를_재정지해도_카드는_영향받지_않는다_멱등성() {
        Map<String, Object> account = createAccount(OWNER_ID);
        String accountId = (String) account.get("accountId");
        Map<String, Object> card = issueCard(OWNER_ID, accountId);
        post("/accounts/" + accountId + "/suspend", OWNER_ID, Map.of());
        waitForCardStatus((String) card.get("cardId"), "SUSPENDED");
        // 계좌를 재개했다가 다시 정지시켜 같은 이벤트 계열(account.suspended.v1)을 한 번 더
        // 발행시킨다 — SuspendCardsByAccountService는 ACTIVE 카드만 대상으로 삼으므로, 이미
        // SUSPENDED인 카드는 두 번째 정지에서 다시 처리되지 않고 SUSPENDED로 그대로 남아야 한다.
        post("/accounts/" + accountId + "/reactivate", OWNER_ID, Map.of());

        ResponseEntity<Map> secondSuspend =
                post("/accounts/" + accountId + "/suspend", OWNER_ID, Map.of());

        assertThat(secondSuspend.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        waitForCardStatus((String) card.get("cardId"), "SUSPENDED");
    }

    @Test
    void 계좌를_종료하면_연결된_카드가_CANCELLED로_바뀐다() {
        Map<String, Object> account = createAccount(OWNER_ID);
        String accountId = (String) account.get("accountId");
        Map<String, Object> card = issueCard(OWNER_ID, accountId);

        ResponseEntity<Map> closeResponse =
                post("/accounts/" + accountId + "/close", OWNER_ID, Map.of());
        assertThat(closeResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        waitForCardStatus((String) card.get("cardId"), "CANCELLED");
    }

    @Test
    void 정지된_카드가_있는_계좌를_종료해도_카드가_CANCELLED로_바뀐다() {
        Map<String, Object> account = createAccount(OWNER_ID);
        String accountId = (String) account.get("accountId");
        Map<String, Object> card = issueCard(OWNER_ID, accountId);
        post("/accounts/" + accountId + "/suspend", OWNER_ID, Map.of());
        waitForCardStatus((String) card.get("cardId"), "SUSPENDED");

        // 잔액이 0인 정지 계좌도 종료할 수 있다 — close()는 상태가 아니라 잔액만 검증한다.
        ResponseEntity<Map> closeResponse =
                post("/accounts/" + accountId + "/close", OWNER_ID, Map.of());
        assertThat(closeResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        waitForCardStatus((String) card.get("cardId"), "CANCELLED");
    }
}

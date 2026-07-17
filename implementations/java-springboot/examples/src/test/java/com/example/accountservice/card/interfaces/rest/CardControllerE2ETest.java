package com.example.accountservice.card.interfaces.rest;

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
 * Card BC의 E2E 테스트. 두 크로스 도메인 흐름을 실제 HTTP API로 검증한다.
 *
 * <ol>
 *   <li>동기 흐름: 카드 발급 시 {@code AccountAdapter}를 통해 실제 Account BC를 조회해 활성/비활성/존재하지 않는 계좌 경로를 모두 확인한다.
 *   <li>비동기 흐름: Account를 정지/해지하는 실제 HTTP 요청이 Outbox를 거쳐 Card BC의 {@code OutboxEventHandler}로 라우팅되어
 *       연결된 카드의 상태가 바뀌는지 확인한다. {@code SuspendAccountService}/{@code CloseAccountService}가 저장 직후
 *       {@code OutboxRelay.processPending()}을 호출하므로 이 흐름은 HTTP 응답이 오기 전에 동기적으로 완결된다 — 별도의 폴링/대기가 필요
 *       없다.
 * </ol>
 */
@Testcontainers
@SuppressWarnings("unchecked")
@SpringBootTest(
        classes = AccountServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CardControllerE2ETest {

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
        // 테스트는 짧은 시간 안에 write API를 기본 limit-for-period(10)보다 훨씬 많이 호출하므로
        // rate limiting 자체가 아니라 각 엔드포인트 로직을 검증할 수 있도록 테스트 한정으로 넉넉하게 푼다.
        registry.add(
                "resilience4j.ratelimiter.instances.http-write.limit-for-period", () -> "1000");
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

        // SuspendAccountService가 저장 직후 OutboxRelay.processPending()을 호출해 Domain Event
        // (AccountSuspended) → Integration Event(account.suspended.v1) → Card BC 반응까지
        // 이 HTTP 요청 하나 안에서 동기적으로 완결되므로, 응답을 받은 시점에 이미 카드 상태가
        // 바뀌어 있어야 한다 — 폴링 없이 바로 검증한다.
        assertThat(get("/cards/" + card1.get("cardId"), OWNER_ID).getBody().get("status"))
                .isEqualTo("SUSPENDED");
        assertThat(get("/cards/" + card2.get("cardId"), OWNER_ID).getBody().get("status"))
                .isEqualTo("SUSPENDED");
    }

    @Test
    void 이미_정지된_카드가_있는_계좌를_재정지해도_카드는_영향받지_않는다_멱등성() {
        Map<String, Object> account = createAccount(OWNER_ID);
        String accountId = (String) account.get("accountId");
        Map<String, Object> card = issueCard(OWNER_ID, accountId);
        post("/accounts/" + accountId + "/suspend", OWNER_ID, Map.of());
        assertThat(get("/cards/" + card.get("cardId"), OWNER_ID).getBody().get("status"))
                .isEqualTo("SUSPENDED");
        // 계좌를 재개했다가 다시 정지시켜 같은 이벤트 계열(account.suspended.v1)을 한 번 더
        // 발행시킨다 — SuspendCardsByAccountService는 ACTIVE 카드만 대상으로 삼으므로, 이미
        // SUSPENDED인 카드는 두 번째 정지에서 다시 처리되지 않고 SUSPENDED로 그대로 남아야 한다.
        post("/accounts/" + accountId + "/reactivate", OWNER_ID, Map.of());

        ResponseEntity<Map> secondSuspend =
                post("/accounts/" + accountId + "/suspend", OWNER_ID, Map.of());

        assertThat(secondSuspend.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(get("/cards/" + card.get("cardId"), OWNER_ID).getBody().get("status"))
                .isEqualTo("SUSPENDED");
    }

    @Test
    void 계좌를_종료하면_연결된_카드가_CANCELLED로_바뀐다() {
        Map<String, Object> account = createAccount(OWNER_ID);
        String accountId = (String) account.get("accountId");
        Map<String, Object> card = issueCard(OWNER_ID, accountId);

        ResponseEntity<Map> closeResponse =
                post("/accounts/" + accountId + "/close", OWNER_ID, Map.of());
        assertThat(closeResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(get("/cards/" + card.get("cardId"), OWNER_ID).getBody().get("status"))
                .isEqualTo("CANCELLED");
    }

    @Test
    void 정지된_카드가_있는_계좌를_종료해도_카드가_CANCELLED로_바뀐다() {
        Map<String, Object> account = createAccount(OWNER_ID);
        String accountId = (String) account.get("accountId");
        Map<String, Object> card = issueCard(OWNER_ID, accountId);
        post("/accounts/" + accountId + "/suspend", OWNER_ID, Map.of());
        assertThat(get("/cards/" + card.get("cardId"), OWNER_ID).getBody().get("status"))
                .isEqualTo("SUSPENDED");

        // 잔액이 0인 정지 계좌도 종료할 수 있다 — close()는 상태가 아니라 잔액만 검증한다.
        ResponseEntity<Map> closeResponse =
                post("/accounts/" + accountId + "/close", OWNER_ID, Map.of());
        assertThat(closeResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(get("/cards/" + card.get("cardId"), OWNER_ID).getBody().get("status"))
                .isEqualTo("CANCELLED");
    }
}

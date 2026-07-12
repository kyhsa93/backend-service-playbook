package com.example.accountservice.account.interfaces.rest;

import com.example.accountservice.AccountServiceApplication;
import org.junit.jupiter.api.BeforeEach;
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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SuppressWarnings("unchecked")
@SpringBootTest(classes = AccountServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountControllerE2ETest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.flyway.enabled", () -> "false");
        // 이 테스트는 같은 클라이언트(IP)로 계좌 생성을 반복 호출한다 — 운영값(5/min, rate-limiting.md)을
        // 그대로 쓰면 rate limiting에 걸려 429가 섞여 나온다. 테스트 목적에 맞게 한도를 완화한다.
        registry.add("resilience4j.ratelimiter.instances.createAccount.limit-for-period", () -> "1000");
        // 테스트는 짧은 시간 안에 write API를 기본 limit-for-period(10)보다 훨씬 많이 호출하므로
        // rate limiting 자체가 아니라 각 엔드포인트 로직을 검증할 수 있도록 테스트 한정으로 넉넉하게 푼다.
        registry.add("resilience4j.ratelimiter.instances.http-write.limit-for-period", () -> "1000");
    }

    @Autowired
    private TestRestTemplate restTemplate;

    private static final String OWNER_ID = "owner-1";
    private static final String OTHER_OWNER_ID = "owner-2";

    private final Map<String, String> tokenCache = new ConcurrentHashMap<>();

    private String tokenFor(String userId) {
        return tokenCache.computeIfAbsent(userId, id -> {
            ResponseEntity<Map> response = restTemplate.postForEntity("/auth/sign-in", Map.of("userId", id), Map.class);
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
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headersFor(ownerId)), Map.class);
    }

    private ResponseEntity<Map> get(String path, String ownerId) {
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headersFor(ownerId)), Map.class);
    }

    private Map<String, Object> createAccount(String ownerId, String currency) {
        ResponseEntity<Map> response = post(
                "/accounts", ownerId, Map.of("currency", currency, "email", ownerId + "@example.com"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    @Test
    void 생성_요청이_유효하면_201과_계좌_정보를_반환한다() {
        ResponseEntity<Map> response = post(
                "/accounts", OWNER_ID, Map.of("currency", "KRW", "email", "owner-1@example.com"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> body = response.getBody();
        assertThat(body.get("ownerId")).isEqualTo(OWNER_ID);
        assertThat(body.get("email")).isEqualTo("owner-1@example.com");
        assertThat(body.get("status")).isEqualTo("ACTIVE");
        assertThat(body.get("accountId")).isNotNull();
        assertThat(body.get("createdAt")).isNotNull();
        Map<String, Object> balance = (Map<String, Object>) body.get("balance");
        assertThat(balance.get("amount")).isEqualTo(0);
        assertThat(balance.get("currency")).isEqualTo("KRW");
    }

    @Test
    void 생성_요청에_이메일이_없으면_400을_반환한다() {
        ResponseEntity<Map> response = post("/accounts", OWNER_ID, Map.of("currency", "KRW"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 입금_요청이_유효하면_201과_거래_내역을_반환한다() {
        Map<String, Object> account = createAccount(OWNER_ID, "KRW");

        ResponseEntity<Map> response = post(
                "/accounts/" + account.get("accountId") + "/deposit", OWNER_ID, Map.of("amount", 10000));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> body = response.getBody();
        assertThat(body.get("accountId")).isEqualTo(account.get("accountId"));
        assertThat(body.get("type")).isEqualTo("DEPOSIT");
        assertThat(body.get("transactionId")).isNotNull();
    }

    @Test
    void 입금_시_존재하지_않는_계좌면_404를_반환한다() {
        ResponseEntity<Map> response = post("/accounts/non-existent/deposit", OWNER_ID, Map.of("amount", 10000));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void 입금_시_다른_소유자의_계좌면_404를_반환한다() {
        Map<String, Object> account = createAccount(OWNER_ID, "KRW");

        ResponseEntity<Map> response = post(
                "/accounts/" + account.get("accountId") + "/deposit", OTHER_OWNER_ID, Map.of("amount", 10000));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void 입금_금액이_0_이하이면_400을_반환한다() {
        Map<String, Object> account = createAccount(OWNER_ID, "KRW");

        ResponseEntity<Map> response = post(
                "/accounts/" + account.get("accountId") + "/deposit", OWNER_ID, Map.of("amount", 0));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("INVALID_AMOUNT");
    }

    @Test
    void 정지된_계좌에_입금하면_400과_DEPOSIT_REQUIRES_ACTIVE_ACCOUNT를_반환한다() {
        Map<String, Object> account = createAccount(OWNER_ID, "KRW");
        post("/accounts/" + account.get("accountId") + "/suspend", OWNER_ID, Map.of());

        ResponseEntity<Map> response = post(
                "/accounts/" + account.get("accountId") + "/deposit", OWNER_ID, Map.of("amount", 10000));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("DEPOSIT_REQUIRES_ACTIVE_ACCOUNT");
    }

    @Test
    void 출금_요청이_유효하면_201과_거래_내역을_반환한다() {
        Map<String, Object> account = createAccount(OWNER_ID, "KRW");
        post("/accounts/" + account.get("accountId") + "/deposit", OWNER_ID, Map.of("amount", 10000));

        ResponseEntity<Map> response = post(
                "/accounts/" + account.get("accountId") + "/withdraw", OWNER_ID, Map.of("amount", 4000));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("type")).isEqualTo("WITHDRAWAL");
    }

    @Test
    void 출금_시_존재하지_않는_계좌면_404를_반환한다() {
        ResponseEntity<Map> response = post("/accounts/non-existent/withdraw", OWNER_ID, Map.of("amount", 1000));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void 잔액보다_큰_금액을_출금하면_400과_INSUFFICIENT_BALANCE를_반환한다() {
        Map<String, Object> account = createAccount(OWNER_ID, "KRW");

        ResponseEntity<Map> response = post(
                "/accounts/" + account.get("accountId") + "/withdraw", OWNER_ID, Map.of("amount", 1000));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("INSUFFICIENT_BALANCE");
    }

    @Test
    void 정지된_계좌에서_출금하면_400과_WITHDRAW_REQUIRES_ACTIVE_ACCOUNT를_반환한다() {
        Map<String, Object> account = createAccount(OWNER_ID, "KRW");
        post("/accounts/" + account.get("accountId") + "/suspend", OWNER_ID, Map.of());

        ResponseEntity<Map> response = post(
                "/accounts/" + account.get("accountId") + "/withdraw", OWNER_ID, Map.of("amount", 1000));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("WITHDRAW_REQUIRES_ACTIVE_ACCOUNT");
    }

    @Test
    void 정상_계좌를_정지하면_204를_반환한다() {
        Map<String, Object> account = createAccount(OWNER_ID, "KRW");

        ResponseEntity<Map> response = post("/accounts/" + account.get("accountId") + "/suspend", OWNER_ID, Map.of());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<Map> getResponse = get("/accounts/" + account.get("accountId"), OWNER_ID);
        assertThat(getResponse.getBody().get("status")).isEqualTo("SUSPENDED");
    }

    @Test
    void 정지_시_존재하지_않는_계좌면_404를_반환한다() {
        ResponseEntity<Map> response = post("/accounts/non-existent/suspend", OWNER_ID, Map.of());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void 이미_정지된_계좌를_정지하면_400과_SUSPEND_REQUIRES_ACTIVE_ACCOUNT를_반환한다() {
        Map<String, Object> account = createAccount(OWNER_ID, "KRW");
        post("/accounts/" + account.get("accountId") + "/suspend", OWNER_ID, Map.of());

        ResponseEntity<Map> response = post("/accounts/" + account.get("accountId") + "/suspend", OWNER_ID, Map.of());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("SUSPEND_REQUIRES_ACTIVE_ACCOUNT");
    }

    @Test
    void 정지된_계좌를_재개하면_204를_반환한다() {
        Map<String, Object> account = createAccount(OWNER_ID, "KRW");
        post("/accounts/" + account.get("accountId") + "/suspend", OWNER_ID, Map.of());

        ResponseEntity<Map> response = post(
                "/accounts/" + account.get("accountId") + "/reactivate", OWNER_ID, Map.of());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<Map> getResponse = get("/accounts/" + account.get("accountId"), OWNER_ID);
        assertThat(getResponse.getBody().get("status")).isEqualTo("ACTIVE");
    }

    @Test
    void 재개_시_존재하지_않는_계좌면_404를_반환한다() {
        ResponseEntity<Map> response = post("/accounts/non-existent/reactivate", OWNER_ID, Map.of());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void 활성_계좌를_재개하면_400과_REACTIVATE_REQUIRES_SUSPENDED_ACCOUNT를_반환한다() {
        Map<String, Object> account = createAccount(OWNER_ID, "KRW");

        ResponseEntity<Map> response = post(
                "/accounts/" + account.get("accountId") + "/reactivate", OWNER_ID, Map.of());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("REACTIVATE_REQUIRES_SUSPENDED_ACCOUNT");
    }

    @Test
    void 잔액이_0인_계좌를_종료하면_204를_반환한다() {
        Map<String, Object> account = createAccount(OWNER_ID, "KRW");

        ResponseEntity<Map> response = post("/accounts/" + account.get("accountId") + "/close", OWNER_ID, Map.of());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<Map> getResponse = get("/accounts/" + account.get("accountId"), OWNER_ID);
        assertThat(getResponse.getBody().get("status")).isEqualTo("CLOSED");
    }

    @Test
    void 종료_시_존재하지_않는_계좌면_404를_반환한다() {
        ResponseEntity<Map> response = post("/accounts/non-existent/close", OWNER_ID, Map.of());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void 잔액이_0이_아니면_400과_ACCOUNT_BALANCE_NOT_ZERO를_반환한다() {
        Map<String, Object> account = createAccount(OWNER_ID, "KRW");
        post("/accounts/" + account.get("accountId") + "/deposit", OWNER_ID, Map.of("amount", 5000));

        ResponseEntity<Map> response = post("/accounts/" + account.get("accountId") + "/close", OWNER_ID, Map.of());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("ACCOUNT_BALANCE_NOT_ZERO");
    }

    @Test
    void 이미_종료된_계좌를_종료하면_400과_ACCOUNT_ALREADY_CLOSED를_반환한다() {
        Map<String, Object> account = createAccount(OWNER_ID, "KRW");
        post("/accounts/" + account.get("accountId") + "/close", OWNER_ID, Map.of());

        ResponseEntity<Map> response = post("/accounts/" + account.get("accountId") + "/close", OWNER_ID, Map.of());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("ACCOUNT_ALREADY_CLOSED");
    }

    @Test
    void 존재하는_계좌를_조회하면_200과_계좌_정보를_반환한다() {
        Map<String, Object> account = createAccount(OWNER_ID, "KRW");

        ResponseEntity<Map> response = get("/accounts/" + account.get("accountId"), OWNER_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body.get("accountId")).isEqualTo(account.get("accountId"));
        assertThat(body.get("ownerId")).isEqualTo(OWNER_ID);
        assertThat(body.get("updatedAt")).isNotNull();
    }

    @Test
    void 조회_시_존재하지_않는_계좌면_404를_반환한다() {
        ResponseEntity<Map> response = get("/accounts/non-existent", OWNER_ID);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("ACCOUNT_NOT_FOUND");
    }

    @Test
    void 다른_소유자가_조회하면_404를_반환한다() {
        Map<String, Object> account = createAccount(OWNER_ID, "KRW");

        ResponseEntity<Map> response = get("/accounts/" + account.get("accountId"), OTHER_OWNER_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void 거래_내역을_페이지네이션과_함께_반환한다() {
        Map<String, Object> account = createAccount(OWNER_ID, "KRW");
        post("/accounts/" + account.get("accountId") + "/deposit", OWNER_ID, Map.of("amount", 10000));
        post("/accounts/" + account.get("accountId") + "/withdraw", OWNER_ID, Map.of("amount", 3000));

        ResponseEntity<Map> response = get(
                "/accounts/" + account.get("accountId") + "/transactions?page=0&take=20", OWNER_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body.get("count")).isEqualTo(2);
        assertThat((java.util.List<?>) body.get("transactions")).hasSize(2);
    }

    @Test
    void 거래_내역_조회_시_존재하지_않는_계좌면_404를_반환한다() {
        ResponseEntity<Map> response = get("/accounts/non-existent/transactions", OWNER_ID);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void take를_초과한_페이지_조회는_빈_배열을_반환한다() {
        Map<String, Object> account = createAccount(OWNER_ID, "KRW");

        ResponseEntity<Map> response = get(
                "/accounts/" + account.get("accountId") + "/transactions?page=5&take=20", OWNER_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("count")).isEqualTo(0);
    }
}

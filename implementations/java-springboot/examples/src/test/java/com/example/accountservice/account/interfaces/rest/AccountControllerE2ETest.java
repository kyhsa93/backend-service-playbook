package com.example.accountservice.account.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.accountservice.AccountServiceApplication;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SuppressWarnings("unchecked")
@AutoConfigureTestRestTemplate
@SpringBootTest(
        classes = AccountServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
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
        // This test repeatedly calls account creation from the same client (IP) — using the
        // production value (5/min, rate-limiting.md) as-is would trigger rate limiting and mix in
        // 429s. Relax the limit for test purposes.
        registry.add(
                "resilience4j.ratelimiter.instances.createAccount.limit-for-period", () -> "1000");
        // The tests call the write API far more times in a short window than the default
        // limit-for-period (10), so we relax it generously for tests only, so that each endpoint's
        // logic is verified rather than rate limiting itself.
        registry.add(
                "resilience4j.ratelimiter.instances.http-write.limit-for-period", () -> "1000");
    }

    @Autowired private TestRestTemplate restTemplate;

    private static final String OWNER_ID = "owner-1";
    private static final String OTHER_OWNER_ID = "owner-2";
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

    private Map<String, Object> createAccount(String ownerId, String currency) {
        ResponseEntity<Map> response =
                post(
                        "/accounts",
                        ownerId,
                        Map.of("currency", currency, "email", ownerId + "@example.com"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    @Test
    void returns_201_and_account_info_when_creation_request_is_valid() {
        ResponseEntity<Map> response =
                post(
                        "/accounts",
                        OWNER_ID,
                        Map.of("currency", "KRW", "email", "owner-1@example.com"));

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
    void returns_400_when_creation_request_has_no_email() {
        ResponseEntity<Map> response = post("/accounts", OWNER_ID, Map.of("currency", "KRW"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void returns_201_and_transaction_info_when_deposit_request_is_valid() {
        Map<String, Object> account = createAccount(OWNER_ID, "KRW");

        ResponseEntity<Map> response =
                post(
                        "/accounts/" + account.get("accountId") + "/deposit",
                        OWNER_ID,
                        Map.of("amount", 10000));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> body = response.getBody();
        assertThat(body.get("accountId")).isEqualTo(account.get("accountId"));
        assertThat(body.get("type")).isEqualTo("DEPOSIT");
        assertThat(body.get("transactionId")).isNotNull();
    }

    @Test
    void returns_404_when_depositing_to_a_nonexistent_account() {
        ResponseEntity<Map> response =
                post("/accounts/non-existent/deposit", OWNER_ID, Map.of("amount", 10000));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void returns_404_when_depositing_to_another_owners_account() {
        Map<String, Object> account = createAccount(OWNER_ID, "KRW");

        ResponseEntity<Map> response =
                post(
                        "/accounts/" + account.get("accountId") + "/deposit",
                        OTHER_OWNER_ID,
                        Map.of("amount", 10000));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void returns_400_when_deposit_amount_is_zero_or_less() {
        Map<String, Object> account = createAccount(OWNER_ID, "KRW");

        ResponseEntity<Map> response =
                post(
                        "/accounts/" + account.get("accountId") + "/deposit",
                        OWNER_ID,
                        Map.of("amount", 0));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("INVALID_AMOUNT");
    }

    @Test
    void returns_400_and_DEPOSIT_REQUIRES_ACTIVE_ACCOUNT_when_depositing_to_a_suspended_account() {
        Map<String, Object> account = createAccount(OWNER_ID, "KRW");
        post("/accounts/" + account.get("accountId") + "/suspend", OWNER_ID, Map.of());

        ResponseEntity<Map> response =
                post(
                        "/accounts/" + account.get("accountId") + "/deposit",
                        OWNER_ID,
                        Map.of("amount", 10000));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("DEPOSIT_REQUIRES_ACTIVE_ACCOUNT");
    }

    @Test
    void returns_201_and_transaction_info_when_withdrawal_request_is_valid() {
        Map<String, Object> account = createAccount(OWNER_ID, "KRW");
        post(
                "/accounts/" + account.get("accountId") + "/deposit",
                OWNER_ID,
                Map.of("amount", 10000));

        ResponseEntity<Map> response =
                post(
                        "/accounts/" + account.get("accountId") + "/withdraw",
                        OWNER_ID,
                        Map.of("amount", 4000));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("type")).isEqualTo("WITHDRAWAL");
    }

    @Test
    void returns_404_when_withdrawing_from_a_nonexistent_account() {
        ResponseEntity<Map> response =
                post("/accounts/non-existent/withdraw", OWNER_ID, Map.of("amount", 1000));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void returns_400_and_INSUFFICIENT_BALANCE_when_withdrawing_more_than_the_balance() {
        Map<String, Object> account = createAccount(OWNER_ID, "KRW");

        ResponseEntity<Map> response =
                post(
                        "/accounts/" + account.get("accountId") + "/withdraw",
                        OWNER_ID,
                        Map.of("amount", 1000));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("INSUFFICIENT_BALANCE");
    }

    @Test
    void
            returns_400_and_WITHDRAW_REQUIRES_ACTIVE_ACCOUNT_when_withdrawing_from_a_suspended_account() {
        Map<String, Object> account = createAccount(OWNER_ID, "KRW");
        post("/accounts/" + account.get("accountId") + "/suspend", OWNER_ID, Map.of());

        ResponseEntity<Map> response =
                post(
                        "/accounts/" + account.get("accountId") + "/withdraw",
                        OWNER_ID,
                        Map.of("amount", 1000));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("WITHDRAW_REQUIRES_ACTIVE_ACCOUNT");
    }

    @Test
    void returns_201_and_withdrawal_deposit_transaction_info_when_transfer_request_is_valid() {
        Map<String, Object> source = createAccount(OWNER_ID, "KRW");
        post(
                "/accounts/" + source.get("accountId") + "/deposit",
                OWNER_ID,
                Map.of("amount", 10000));
        Map<String, Object> target = createAccount(OTHER_OWNER_ID, "KRW");

        ResponseEntity<Map> response =
                post(
                        "/accounts/" + source.get("accountId") + "/transfer",
                        OWNER_ID,
                        Map.of("targetAccountId", target.get("accountId"), "amount", 4000));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> body = response.getBody();
        assertThat(body.get("transferId")).isNotNull();
        Map<String, Object> sourceTx = (Map<String, Object>) body.get("sourceTransaction");
        Map<String, Object> targetTx = (Map<String, Object>) body.get("targetTransaction");
        assertThat(sourceTx.get("type")).isEqualTo("WITHDRAWAL");
        assertThat(targetTx.get("type")).isEqualTo("DEPOSIT");

        ResponseEntity<Map> sourceGet = get("/accounts/" + source.get("accountId"), OWNER_ID);
        Map<String, Object> sourceBalance =
                (Map<String, Object>) sourceGet.getBody().get("balance");
        assertThat(sourceBalance.get("amount")).isEqualTo(6000);

        ResponseEntity<Map> targetGet = get("/accounts/" + target.get("accountId"), OTHER_OWNER_ID);
        Map<String, Object> targetBalance =
                (Map<String, Object>) targetGet.getBody().get("balance");
        assertThat(targetBalance.get("amount")).isEqualTo(4000);
    }

    @Test
    void can_transfer_to_an_account_owned_by_someone_else() {
        Map<String, Object> source = createAccount(OWNER_ID, "KRW");
        post(
                "/accounts/" + source.get("accountId") + "/deposit",
                OWNER_ID,
                Map.of("amount", 10000));
        Map<String, Object> target = createAccount(OTHER_OWNER_ID, "KRW");

        ResponseEntity<Map> response =
                post(
                        "/accounts/" + source.get("accountId") + "/transfer",
                        OWNER_ID,
                        Map.of("targetAccountId", target.get("accountId"), "amount", 1000));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void returns_404_when_transfer_source_account_is_not_found() {
        Map<String, Object> target = createAccount(OTHER_OWNER_ID, "KRW");

        ResponseEntity<Map> response =
                post(
                        "/accounts/non-existent/transfer",
                        OWNER_ID,
                        Map.of("targetAccountId", target.get("accountId"), "amount", 1000));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("ACCOUNT_NOT_FOUND");
    }

    @Test
    void returns_404_when_transfer_target_account_is_not_found() {
        Map<String, Object> source = createAccount(OWNER_ID, "KRW");
        post(
                "/accounts/" + source.get("accountId") + "/deposit",
                OWNER_ID,
                Map.of("amount", 10000));

        ResponseEntity<Map> response =
                post(
                        "/accounts/" + source.get("accountId") + "/transfer",
                        OWNER_ID,
                        Map.of("targetAccountId", "non-existent", "amount", 1000));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("ACCOUNT_NOT_FOUND");
    }

    @Test
    void returns_400_and_TRANSFER_SAME_ACCOUNT_when_source_and_target_accounts_are_the_same() {
        Map<String, Object> account = createAccount(OWNER_ID, "KRW");
        post(
                "/accounts/" + account.get("accountId") + "/deposit",
                OWNER_ID,
                Map.of("amount", 10000));

        ResponseEntity<Map> response =
                post(
                        "/accounts/" + account.get("accountId") + "/transfer",
                        OWNER_ID,
                        Map.of("targetAccountId", account.get("accountId"), "amount", 1000));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("TRANSFER_SAME_ACCOUNT");
    }

    @Test
    void returns_400_and_INSUFFICIENT_BALANCE_when_transferring_more_than_the_balance() {
        Map<String, Object> source = createAccount(OWNER_ID, "KRW");
        Map<String, Object> target = createAccount(OTHER_OWNER_ID, "KRW");

        ResponseEntity<Map> response =
                post(
                        "/accounts/" + source.get("accountId") + "/transfer",
                        OWNER_ID,
                        Map.of("targetAccountId", target.get("accountId"), "amount", 1000));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("INSUFFICIENT_BALANCE");
    }

    @Test
    void
            returns_400_and_WITHDRAW_REQUIRES_ACTIVE_ACCOUNT_when_transfer_source_account_is_suspended() {
        Map<String, Object> source = createAccount(OWNER_ID, "KRW");
        post(
                "/accounts/" + source.get("accountId") + "/deposit",
                OWNER_ID,
                Map.of("amount", 10000));
        post("/accounts/" + source.get("accountId") + "/suspend", OWNER_ID, Map.of());
        Map<String, Object> target = createAccount(OTHER_OWNER_ID, "KRW");

        ResponseEntity<Map> response =
                post(
                        "/accounts/" + source.get("accountId") + "/transfer",
                        OWNER_ID,
                        Map.of("targetAccountId", target.get("accountId"), "amount", 1000));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("WITHDRAW_REQUIRES_ACTIVE_ACCOUNT");
    }

    @Test
    void
            returns_400_and_DEPOSIT_REQUIRES_ACTIVE_ACCOUNT_when_transfer_target_account_is_suspended() {
        Map<String, Object> source = createAccount(OWNER_ID, "KRW");
        post(
                "/accounts/" + source.get("accountId") + "/deposit",
                OWNER_ID,
                Map.of("amount", 10000));
        Map<String, Object> target = createAccount(OTHER_OWNER_ID, "KRW");
        post("/accounts/" + target.get("accountId") + "/suspend", OTHER_OWNER_ID, Map.of());

        ResponseEntity<Map> response =
                post(
                        "/accounts/" + source.get("accountId") + "/transfer",
                        OWNER_ID,
                        Map.of("targetAccountId", target.get("accountId"), "amount", 1000));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("DEPOSIT_REQUIRES_ACTIVE_ACCOUNT");
    }

    @Test
    void returns_400_and_CURRENCY_MISMATCH_on_transfer_currency_mismatch() {
        Map<String, Object> source = createAccount(OWNER_ID, "KRW");
        post(
                "/accounts/" + source.get("accountId") + "/deposit",
                OWNER_ID,
                Map.of("amount", 10000));
        Map<String, Object> target = createAccount(OTHER_OWNER_ID, "USD");

        ResponseEntity<Map> response =
                post(
                        "/accounts/" + source.get("accountId") + "/transfer",
                        OWNER_ID,
                        Map.of("targetAccountId", target.get("accountId"), "amount", 1000));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("CURRENCY_MISMATCH");
    }

    @Test
    void returns_204_when_suspending_a_normal_account() {
        Map<String, Object> account = createAccount(OWNER_ID, "KRW");

        ResponseEntity<Map> response =
                post("/accounts/" + account.get("accountId") + "/suspend", OWNER_ID, Map.of());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<Map> getResponse = get("/accounts/" + account.get("accountId"), OWNER_ID);
        assertThat(getResponse.getBody().get("status")).isEqualTo("SUSPENDED");
    }

    @Test
    void returns_404_when_suspending_a_nonexistent_account() {
        ResponseEntity<Map> response = post("/accounts/non-existent/suspend", OWNER_ID, Map.of());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void
            returns_400_and_SUSPEND_REQUIRES_ACTIVE_ACCOUNT_when_suspending_an_already_suspended_account() {
        Map<String, Object> account = createAccount(OWNER_ID, "KRW");
        post("/accounts/" + account.get("accountId") + "/suspend", OWNER_ID, Map.of());

        ResponseEntity<Map> response =
                post("/accounts/" + account.get("accountId") + "/suspend", OWNER_ID, Map.of());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("SUSPEND_REQUIRES_ACTIVE_ACCOUNT");
    }

    @Test
    void returns_204_when_reactivating_a_suspended_account() {
        Map<String, Object> account = createAccount(OWNER_ID, "KRW");
        post("/accounts/" + account.get("accountId") + "/suspend", OWNER_ID, Map.of());

        ResponseEntity<Map> response =
                post("/accounts/" + account.get("accountId") + "/reactivate", OWNER_ID, Map.of());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<Map> getResponse = get("/accounts/" + account.get("accountId"), OWNER_ID);
        assertThat(getResponse.getBody().get("status")).isEqualTo("ACTIVE");
    }

    @Test
    void returns_404_when_reactivating_a_nonexistent_account() {
        ResponseEntity<Map> response =
                post("/accounts/non-existent/reactivate", OWNER_ID, Map.of());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void
            returns_400_and_REACTIVATE_REQUIRES_SUSPENDED_ACCOUNT_when_reactivating_an_active_account() {
        Map<String, Object> account = createAccount(OWNER_ID, "KRW");

        ResponseEntity<Map> response =
                post("/accounts/" + account.get("accountId") + "/reactivate", OWNER_ID, Map.of());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code"))
                .isEqualTo("REACTIVATE_REQUIRES_SUSPENDED_ACCOUNT");
    }

    @Test
    void returns_204_when_closing_a_zero_balance_account() {
        Map<String, Object> account = createAccount(OWNER_ID, "KRW");

        ResponseEntity<Map> response =
                post("/accounts/" + account.get("accountId") + "/close", OWNER_ID, Map.of());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<Map> getResponse = get("/accounts/" + account.get("accountId"), OWNER_ID);
        assertThat(getResponse.getBody().get("status")).isEqualTo("CLOSED");
    }

    @Test
    void returns_404_when_closing_a_nonexistent_account() {
        ResponseEntity<Map> response = post("/accounts/non-existent/close", OWNER_ID, Map.of());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void returns_400_and_ACCOUNT_BALANCE_NOT_ZERO_when_balance_is_not_zero() {
        Map<String, Object> account = createAccount(OWNER_ID, "KRW");
        post(
                "/accounts/" + account.get("accountId") + "/deposit",
                OWNER_ID,
                Map.of("amount", 5000));

        ResponseEntity<Map> response =
                post("/accounts/" + account.get("accountId") + "/close", OWNER_ID, Map.of());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("ACCOUNT_BALANCE_NOT_ZERO");
    }

    @Test
    void returns_400_and_ACCOUNT_ALREADY_CLOSED_when_closing_an_already_closed_account() {
        Map<String, Object> account = createAccount(OWNER_ID, "KRW");
        post("/accounts/" + account.get("accountId") + "/close", OWNER_ID, Map.of());

        ResponseEntity<Map> response =
                post("/accounts/" + account.get("accountId") + "/close", OWNER_ID, Map.of());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("ACCOUNT_ALREADY_CLOSED");
    }

    @Test
    void returns_200_and_account_info_when_fetching_an_existing_account() {
        Map<String, Object> account = createAccount(OWNER_ID, "KRW");

        ResponseEntity<Map> response = get("/accounts/" + account.get("accountId"), OWNER_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body.get("accountId")).isEqualTo(account.get("accountId"));
        assertThat(body.get("ownerId")).isEqualTo(OWNER_ID);
        assertThat(body.get("updatedAt")).isNotNull();
    }

    @Test
    void returns_404_when_fetching_a_nonexistent_account() {
        ResponseEntity<Map> response = get("/accounts/non-existent", OWNER_ID);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("ACCOUNT_NOT_FOUND");
    }

    @Test
    void returns_404_when_fetched_by_a_different_owner() {
        Map<String, Object> account = createAccount(OWNER_ID, "KRW");

        ResponseEntity<Map> response = get("/accounts/" + account.get("accountId"), OTHER_OWNER_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void returns_transaction_history_with_pagination() {
        Map<String, Object> account = createAccount(OWNER_ID, "KRW");
        post(
                "/accounts/" + account.get("accountId") + "/deposit",
                OWNER_ID,
                Map.of("amount", 10000));
        post(
                "/accounts/" + account.get("accountId") + "/withdraw",
                OWNER_ID,
                Map.of("amount", 3000));

        ResponseEntity<Map> response =
                get(
                        "/accounts/" + account.get("accountId") + "/transactions?page=0&take=20",
                        OWNER_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body.get("count")).isEqualTo(2);
        assertThat((java.util.List<?>) body.get("transactions")).hasSize(2);
    }

    @Test
    void returns_404_when_fetching_transaction_history_for_a_nonexistent_account() {
        ResponseEntity<Map> response = get("/accounts/non-existent/transactions", OWNER_ID);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void returns_an_empty_array_for_a_page_beyond_take() {
        Map<String, Object> account = createAccount(OWNER_ID, "KRW");

        ResponseEntity<Map> response =
                get(
                        "/accounts/" + account.get("accountId") + "/transactions?page=5&take=20",
                        OWNER_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("count")).isEqualTo(0);
    }
}

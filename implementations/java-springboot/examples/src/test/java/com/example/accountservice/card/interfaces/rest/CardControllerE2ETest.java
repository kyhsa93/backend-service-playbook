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
 * E2E test for the Card BC. Verifies two cross-domain flows through the real HTTP API.
 *
 * <ol>
 *   <li>Synchronous flow: when a card is issued, it looks up the real Account BC through {@code
 *       AccountAdapter}, covering the active/inactive/non-existent account paths.
 *   <li>Asynchronous flow: a real HTTP request that suspends/closes an Account is routed through
 *       Outbox → SQS (LocalStack) to the Card BC's {@code OutboxEventHandler}, confirming that the
 *       linked card's status changes. Since {@code OutboxPoller} (1-second cycle) has to publish
 *       the event to SQS and {@code OutboxConsumer} (long polling) has to receive it before it is
 *       processed, the card status may not have changed yet at the moment the HTTP response is
 *       received — verified by polling with {@code waitForCardStatus}.
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

    // The @DynamicPropertySource supplier can be invoked every time a property is looked up
    // (possibly multiple times), so the queue is created only once and the result is cached for
    // reuse on subsequent calls.
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
        // The tests call the write API far more times in a short window than the default
        // limit-for-period (10), so we relax it generously for tests only, so that each endpoint's
        // logic is verified rather than rate limiting itself.
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

    // When an Account is suspended/closed, AccountSuspended/AccountClosed (Domain Event) →
    // account.suspended.v1/account.closed.v1 (Integration Event) is written to the Outbox, and
    // only once OutboxPoller (1-second cycle) publishes it to SQS and OutboxConsumer (long
    // polling) receives it and runs the Card BC's OutboxEventHandler does the card status change
    // — it may not be reflected yet at the moment of the HTTP response, so we poll. We allow a
    // generous timeout to account for the real SQS (LocalStack) round-trip delay (1-second poll
    // interval + long-poll wait). untilAsserted exposes the last AssertionError (actual vs.
    // expected) as-is on timeout.
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
    void returns_201_and_an_ACTIVE_card_when_issuing_to_an_active_account() {
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
    void returns_404_and_LINKED_ACCOUNT_NOT_FOUND_when_issuing_to_a_nonexistent_account() {
        ResponseEntity<Map> response =
                post("/cards", OWNER_ID, Map.of("accountId", "non-existent", "brand", "VISA"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("LINKED_ACCOUNT_NOT_FOUND");
    }

    @Test
    void returns_404_and_LINKED_ACCOUNT_NOT_FOUND_when_issuing_to_another_owners_account() {
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
    void returns_400_and_CARD_ISSUE_REQUIRES_ACTIVE_ACCOUNT_when_issuing_to_a_suspended_account() {
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
    void returns_200_and_card_info_when_fetching_an_issued_card() {
        Map<String, Object> account = createAccount(OWNER_ID);
        Map<String, Object> card = issueCard(OWNER_ID, (String) account.get("accountId"));

        ResponseEntity<Map> response = get("/cards/" + card.get("cardId"), OWNER_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("cardId")).isEqualTo(card.get("cardId"));
    }

    @Test
    void returns_404_and_CARD_NOT_FOUND_when_fetching_a_nonexistent_card() {
        ResponseEntity<Map> response = get("/cards/non-existent", OWNER_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("CARD_NOT_FOUND");
    }

    @Test
    void suspending_the_account_turns_all_linked_ACTIVE_cards_into_SUSPENDED() {
        Map<String, Object> account = createAccount(OWNER_ID);
        String accountId = (String) account.get("accountId");
        Map<String, Object> card1 = issueCard(OWNER_ID, accountId);
        Map<String, Object> card2 = issueCard(OWNER_ID, accountId);

        ResponseEntity<Map> suspendResponse =
                post("/accounts/" + accountId + "/suspend", OWNER_ID, Map.of());
        assertThat(suspendResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // Only once OutboxPoller (1-second cycle) publishes AccountSuspended (Domain Event) →
        // account.suspended.v1 (Integration Event) to SQS, and OutboxConsumer (long polling)
        // receives it and runs the Card BC's OutboxEventHandler, does the card status change —
        // since this flow is asynchronous, it may not be reflected yet at the moment the response
        // is received. Verify by polling.
        waitForCardStatus((String) card1.get("cardId"), "SUSPENDED");
        waitForCardStatus((String) card2.get("cardId"), "SUSPENDED");
    }

    @Test
    void
            re_suspending_an_account_that_already_has_a_suspended_card_does_not_affect_the_card_idempotency() {
        Map<String, Object> account = createAccount(OWNER_ID);
        String accountId = (String) account.get("accountId");
        Map<String, Object> card = issueCard(OWNER_ID, accountId);
        post("/accounts/" + accountId + "/suspend", OWNER_ID, Map.of());
        waitForCardStatus((String) card.get("cardId"), "SUSPENDED");
        // Reactivate the account and then suspend it again, publishing the same event series
        // (account.suspended.v1) once more — since SuspendCardsByAccountService only targets
        // ACTIVE cards, a card that is already SUSPENDED must not be processed again on the
        // second suspension and must remain SUSPENDED.
        post("/accounts/" + accountId + "/reactivate", OWNER_ID, Map.of());

        ResponseEntity<Map> secondSuspend =
                post("/accounts/" + accountId + "/suspend", OWNER_ID, Map.of());

        assertThat(secondSuspend.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        waitForCardStatus((String) card.get("cardId"), "SUSPENDED");
    }

    @Test
    void closing_the_account_turns_the_linked_card_into_CANCELLED() {
        Map<String, Object> account = createAccount(OWNER_ID);
        String accountId = (String) account.get("accountId");
        Map<String, Object> card = issueCard(OWNER_ID, accountId);

        ResponseEntity<Map> closeResponse =
                post("/accounts/" + accountId + "/close", OWNER_ID, Map.of());
        assertThat(closeResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        waitForCardStatus((String) card.get("cardId"), "CANCELLED");
    }

    @Test
    void closing_an_account_that_has_a_suspended_card_still_turns_the_card_into_CANCELLED() {
        Map<String, Object> account = createAccount(OWNER_ID);
        String accountId = (String) account.get("accountId");
        Map<String, Object> card = issueCard(OWNER_ID, accountId);
        post("/accounts/" + accountId + "/suspend", OWNER_ID, Map.of());
        waitForCardStatus((String) card.get("cardId"), "SUSPENDED");

        // A suspended account with a zero balance can also be closed — close() only checks the
        // balance, not the status.
        ResponseEntity<Map> closeResponse =
                post("/accounts/" + accountId + "/close", OWNER_ID, Map.of());
        assertThat(closeResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        waitForCardStatus((String) card.get("cardId"), "CANCELLED");
    }
}

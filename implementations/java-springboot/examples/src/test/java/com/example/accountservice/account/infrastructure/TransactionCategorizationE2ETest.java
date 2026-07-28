package com.example.accountservice.account.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.accountservice.AccountServiceApplication;
import com.example.accountservice.support.SqsTestQueue;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
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
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.VerifyEmailIdentityRequest;

/**
 * Exercises the real async pipeline end to end: withdraw (with a merchantName) → {@code
 * MoneyWithdrawnEvent} → Outbox → SQS → {@code OutboxConsumer} → {@code OutboxEventDispatcher} →
 * {@code CategorizeTransactionEventHandler} → the {@code TransactionRepository} write. The LLM
 * behind {@code TransactionAutoCategorizer} isn't available in this e2e environment (same reasoning
 * as the {@code /transactions/ask} tests), so the categorization call itself falls back to {@code
 * OTHER} — but this still proves the whole plumbing runs, including that {@code
 * MoneyWithdrawnEventHandler} (SES notification) and {@code CategorizeTransactionEventHandler} both
 * run for the same delivery (the "1:N" contract of {@code OutboxEventDispatcher}).
 */
@Testcontainers
@SuppressWarnings("unchecked")
@AutoConfigureTestRestTemplate
@SpringBootTest(
        classes = AccountServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TransactionCategorizationE2ETest {

    private static final String SENDER_EMAIL = "no-reply@backend-service-playbook.example.com";
    private static final String OWNER_ID = "owner-1";
    private static final String RECIPIENT_EMAIL = "owner1@example.com";
    private static final String PASSWORD = "password123!";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static LocalStackContainer localstack =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.0"))
                    .withServices(LocalStackContainer.Service.SES, LocalStackContainer.Service.SQS);

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

        registry.add("aws.region", () -> localstack.getRegion());
        registry.add(
                "aws.endpoint-url",
                () -> localstack.getEndpointOverride(LocalStackContainer.Service.SES).toString());
        registry.add("aws.access-key-id", () -> localstack.getAccessKey());
        registry.add("aws.secret-access-key", () -> localstack.getSecretKey());
        registry.add("ses.sender-email", () -> SENDER_EMAIL);
        registry.add(
                "sqs.domain-event-queue-url",
                TransactionCategorizationE2ETest::domainEventQueueUrl);
        registry.add(
                "resilience4j.ratelimiter.instances.http-write.limit-for-period", () -> "1000");
    }

    @BeforeAll
    static void verifySenderIdentity() {
        try (SesClient sesClient =
                SesClient.builder()
                        .region(Region.of(localstack.getRegion()))
                        .endpointOverride(
                                localstack.getEndpointOverride(LocalStackContainer.Service.SES))
                        .credentialsProvider(
                                StaticCredentialsProvider.create(
                                        AwsBasicCredentials.create(
                                                localstack.getAccessKey(),
                                                localstack.getSecretKey())))
                        .build()) {
            sesClient.verifyEmailIdentity(
                    VerifyEmailIdentityRequest.builder().emailAddress(SENDER_EMAIL).build());
        }
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

    private Map<String, Object> createAccountAndDeposit(long depositAmount) {
        ResponseEntity<Map> createResponse =
                post("/accounts", OWNER_ID, Map.of("currency", "KRW", "email", RECIPIENT_EMAIL));
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String accountId = (String) createResponse.getBody().get("accountId");

        ResponseEntity<Map> depositResponse =
                post(
                        "/accounts/" + accountId + "/deposit",
                        OWNER_ID,
                        Map.of("amount", depositAmount));
        assertThat(depositResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return Map.of("accountId", accountId);
    }

    private Map<String, Object> firstWithdrawalTransaction(String accountId) {
        ResponseEntity<Map> response =
                get("/accounts/" + accountId + "/transactions?type=WITHDRAWAL", OWNER_ID);
        List<Map<String, Object>> transactions =
                (List<Map<String, Object>>) response.getBody().get("transactions");
        return transactions.isEmpty() ? null : transactions.get(0);
    }

    @Test
    void withdraw_with_a_merchantName_then_the_transaction_is_asynchronously_categorized() {
        String accountId = (String) createAccountAndDeposit(10000).get("accountId");

        ResponseEntity<Map> withdrawResponse =
                post(
                        "/accounts/" + accountId + "/withdraw",
                        OWNER_ID,
                        Map.of("amount", 5500, "merchantName", "Starbucks Gangnam"));
        assertThat(withdrawResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(
                        () -> {
                            Map<String, Object> transaction = firstWithdrawalTransaction(accountId);
                            assertThat(transaction).isNotNull();
                            assertThat(transaction.get("merchantName"))
                                    .isEqualTo("Starbucks Gangnam");
                            assertThat(transaction.get("category")).isEqualTo("OTHER");
                        });
    }

    @Test
    void withdraw_without_a_merchantName_then_the_transaction_is_never_categorized()
            throws InterruptedException {
        String accountId = (String) createAccountAndDeposit(10000).get("accountId");

        ResponseEntity<Map> withdrawResponse =
                post("/accounts/" + accountId + "/withdraw", OWNER_ID, Map.of("amount", 5500));
        assertThat(withdrawResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // No merchantName to react to, so there's nothing to wait out — give the (skipped)
        // reaction the same window as the happy path would need, then assert it never ran.
        Thread.sleep(5000);
        Map<String, Object> transaction = firstWithdrawalTransaction(accountId);

        assertThat(transaction).isNotNull();
        assertThat(transaction.get("merchantName")).isNull();
        assertThat(transaction.get("category")).isNull();
    }
}

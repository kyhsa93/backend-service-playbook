package com.example.accountservice.account.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.accountservice.AccountServiceApplication;
import com.example.accountservice.account.infrastructure.notification.persistence.SentEmail;
import com.example.accountservice.account.infrastructure.notification.persistence.SentEmailRepository;
import com.example.accountservice.support.SqsTestQueue;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * Exercises the real async pipeline end to end: withdraw → {@code MoneyWithdrawnEvent} → Outbox →
 * SQS → {@code OutboxConsumer} → {@code OutboxEventDispatcher} → {@code
 * DetectWithdrawalAnomalyEventHandler} → a real SES send (via LocalStack) verified through the
 * persisted {@code SentEmail} row — the same pattern {@code TransactionCategorizationE2ETest} uses.
 * With this handler added, the same {@code MoneyWithdrawn} delivery now fans out to 3 subscribers
 * ({@code MoneyWithdrawnEventHandler}, {@code CategorizeTransactionEventHandler}, and this one),
 * all registered on {@code OutboxEventDispatcher}'s 1:N contract.
 */
@Testcontainers
@SuppressWarnings("unchecked")
@AutoConfigureTestRestTemplate
@SpringBootTest(
        classes = AccountServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WithdrawalAnomalyAlertE2ETest {

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
                "sqs.domain-event-queue-url", WithdrawalAnomalyAlertE2ETest::domainEventQueueUrl);
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
    @Autowired private SentEmailRepository sentEmailRepository;

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

    private String createAccountAndDeposit(long depositAmount) {
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
        return accountId;
    }

    private void withdraw(String accountId, long amount) {
        ResponseEntity<Map> response =
                post("/accounts/" + accountId + "/withdraw", OWNER_ID, Map.of("amount", amount));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private void withdraw(String accountId, long amount, String merchantName) {
        ResponseEntity<Map> response =
                post(
                        "/accounts/" + accountId + "/withdraw",
                        OWNER_ID,
                        Map.of("amount", amount, "merchantName", merchantName));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private Map<String, Object> firstWithdrawalTransaction(String accountId) {
        ResponseEntity<Map> response =
                restTemplate.exchange(
                        "/accounts/" + accountId + "/transactions?type=WITHDRAWAL",
                        HttpMethod.GET,
                        new HttpEntity<>(headersFor(OWNER_ID)),
                        Map.class);
        List<Map<String, Object>> transactions =
                (List<Map<String, Object>>) response.getBody().get("transactions");
        return transactions.isEmpty() ? null : transactions.get(0);
    }

    @Test
    void a_withdrawal_far_outside_the_accounts_normal_range_then_an_alert_email_is_sent() {
        String accountId = createAccountAndDeposit(10_000_000);

        // Builds a normal history of small, similar withdrawals — AnomalyDetectionService needs
        // at least 5 to compute a meaningful baseline.
        for (long amount : new long[] {10000, 12000, 9000, 11000, 10500}) {
            withdraw(accountId, amount);
        }
        // Far beyond that history's spread — a genuine statistical outlier. Also carries a
        // merchantName so CategorizeTransactionEventHandler has something to react to, proving
        // all 3 MoneyWithdrawn subscribers (MoneyWithdrawnEventHandler, CategorizeTransaction-
        // EventHandler, DetectWithdrawalAnomalyEventHandler) actually run for the same delivery —
        // the OutboxEventDispatcher 1:N contract this feature specifically exercises with a 3rd
        // handler.
        withdraw(accountId, 5_000_000, "Suspicious Wire Transfer");

        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(
                        () -> {
                            // Handler 3: DetectWithdrawalAnomalyEventHandler sent the alert.
                            Optional<SentEmail> alertEmail =
                                    sentEmailRepository.findByAccountIdAndEventType(
                                            accountId, "WithdrawalAnomalyDetected");
                            assertThat(alertEmail).isPresent();
                            assertThat(alertEmail.get().getRecipient()).isEqualTo(RECIPIENT_EMAIL);

                            // Handler 1: MoneyWithdrawnEventHandler sent a completion email for
                            // every withdrawal (6 in this test, one per withdraw() call) — so
                            // this is a findAll+filter rather than
                            // findByAccountIdAndEventType, which is a single-result lookup and
                            // would throw NonUniqueResultException here.
                            boolean completionEmailSent =
                                    sentEmailRepository.findAll().stream()
                                            .anyMatch(
                                                    email ->
                                                            email.getAccountId().equals(accountId)
                                                                    && email.getEventType()
                                                                            .equals(
                                                                                    "MoneyWithdrawn"));
                            assertThat(completionEmailSent).isTrue();

                            // Handler 2: CategorizeTransactionEventHandler categorized the
                            // transaction (falls back to OTHER — no LLM available in this e2e
                            // environment, same as TransactionCategorizationE2ETest).
                            Map<String, Object> transaction = firstWithdrawalTransaction(accountId);
                            assertThat(transaction).isNotNull();
                            assertThat(transaction.get("category")).isEqualTo("OTHER");
                        });
    }

    @Test
    void withdrawals_that_stay_within_the_accounts_normal_range_then_no_alert_email_is_ever_sent()
            throws InterruptedException {
        String accountId = createAccountAndDeposit(10_000_000);

        for (long amount : new long[] {10000, 12000, 9000, 11000, 10500, 10800}) {
            withdraw(accountId, amount);
        }

        // No single "the async work finished" signal to await for a negative case — give the
        // pipeline the same window the positive test needs, then assert nothing landed.
        Thread.sleep(5000);
        Optional<SentEmail> alertEmail =
                sentEmailRepository.findByAccountIdAndEventType(
                        accountId, "WithdrawalAnomalyDetected");

        assertThat(alertEmail).isEmpty();
    }
}

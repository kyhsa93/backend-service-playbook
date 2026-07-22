package com.example.accountservice.card.infrastructure.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.accountservice.AccountServiceApplication;
import com.example.accountservice.card.infrastructure.notification.persistence.CardSentEmail;
import com.example.accountservice.card.infrastructure.notification.persistence.CardSentEmailRepository;
import com.example.accountservice.card.infrastructure.scheduling.CardStatementScheduler;
import com.example.accountservice.support.SqsTestQueue;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
 * E2E test verifying the actual infrastructure round trip for the monthly card statement send
 * (scheduling.md Feature 2). Instead of waiting for a real Cron tick (4 AM on the 1st of each
 * month), it calls {@link CardStatementScheduler#enqueueMonthlyStatement()} directly, exercising
 * the full path: Scheduler → task_outbox → TaskOutboxPoller → Task Queue (SQS FIFO, LocalStack) →
 * TaskConsumer → SendCardStatementTaskController → SendCardStatementService → a Payment BC lookup
 * (ACL) → NotificationService (SES). Since {@code CardSentEmail} belongs to this package
 * (package-private), this test is placed in the same package (same reason as NotificationE2ETest
 * under account/infrastructure/notification/).
 */
@Testcontainers
@SuppressWarnings("unchecked")
@AutoConfigureTestRestTemplate
@SpringBootTest(
        classes = AccountServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CardStatementSchedulingE2ETest {

    private static final String SENDER_EMAIL = "no-reply@backend-service-playbook.example.com";
    private static final String OWNER_ID = "statement-owner-1";
    private static final String RECIPIENT_EMAIL = "statement-owner1@example.com";
    private static final String PASSWORD = "password123!";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static LocalStackContainer localstack =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.0"))
                    .withServices(LocalStackContainer.Service.SES, LocalStackContainer.Service.SQS);

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
                () -> localstack.getEndpointOverride(LocalStackContainer.Service.SES).toString());
        registry.add("aws.access-key-id", () -> localstack.getAccessKey());
        registry.add("aws.secret-access-key", () -> localstack.getSecretKey());
        registry.add("ses.sender-email", () -> SENDER_EMAIL);
        registry.add(
                "sqs.domain-event-queue-url", CardStatementSchedulingE2ETest::domainEventQueueUrl);
        registry.add("sqs.task-queue-url", CardStatementSchedulingE2ETest::taskQueueUrl);
    }

    // LocalStack SES throws a MessageRejectedException if sendEmail is called with an unverified
    // sender address (the same constraint as real SES's sandbox mode) — for the same reason as
    // account/infrastructure/notification/NotificationE2ETest, we pre-verify the sender address
    // before the test starts.
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
    @Autowired private CardStatementScheduler cardStatementScheduler;
    @Autowired private CardSentEmailRepository cardSentEmailRepository;

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

    private List<Map<String, Object>> fetchSesMessages() throws Exception {
        String url =
                "http://"
                        + localstack.getHost()
                        + ":"
                        + localstack.getMappedPort(4566)
                        + "/_aws/ses";
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        Map<String, Object> body =
                new ObjectMapper().readValue(response.body(), new TypeReference<>() {});
        return (List<Map<String, Object>>) body.get("messages");
    }

    private Optional<CardSentEmail> waitForStatementEmail(String cardId) {
        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .until(
                        () ->
                                cardSentEmailRepository.findByCardIdAndEventType(
                                        cardId, "CardStatement"),
                        Optional::isPresent);
        return cardSentEmailRepository.findByCardIdAndEventType(cardId, "CardStatement");
    }

    @Test
    void
            enqueueing_this_months_card_statement_notice_sends_an_email_with_the_usage_count_and_total()
                    throws Exception {
        // Open an account + issue a card + make 2 payments (totaling 30,000 KRW).
        ResponseEntity<Map> account =
                post("/accounts", OWNER_ID, Map.of("currency", "KRW", "email", RECIPIENT_EMAIL));
        assertThat(account.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String accountId = (String) account.getBody().get("accountId");
        post("/accounts/" + accountId + "/deposit", OWNER_ID, Map.of("amount", 1_000_000));

        ResponseEntity<Map> card =
                post("/cards", OWNER_ID, Map.of("accountId", accountId, "brand", "VISA"));
        assertThat(card.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String cardId = (String) card.getBody().get("cardId");

        ResponseEntity<Map> payment1 =
                post("/payments", OWNER_ID, Map.of("cardId", cardId, "amount", 10_000));
        assertThat(payment1.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(payment1.getBody().get("status")).isEqualTo("COMPLETED");
        ResponseEntity<Map> payment2 =
                post("/payments", OWNER_ID, Map.of("cardId", cardId, "amount", 20_000));
        assertThat(payment2.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(payment2.getBody().get("status")).isEqualTo("COMPLETED");

        cardStatementScheduler.enqueueMonthlyStatement();

        CardSentEmail sentEmail = waitForStatementEmail(cardId).orElseThrow();
        assertThat(sentEmail.getRecipient()).isEqualTo(RECIPIENT_EMAIL);
        assertThat(sentEmail.getSubject()).contains("card statement");
        assertThat(sentEmail.getSesMessageId()).isNotBlank();

        // Confirm that the message body LocalStack SES actually received precisely contains the
        // payment count (2) and total (30000) — evidence that PaymentAdapter (ACL) correctly
        // aggregated and passed along the actual Payment BC data.
        List<Map<String, Object>> messages = fetchSesMessages();
        Map<String, Object> matched =
                messages.stream()
                        .filter(m -> sentEmail.getSesMessageId().equals(m.get("Id")))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new AssertionError(
                                                "Could not find the matching message in localstack SES."));
        // LocalStack's /_aws/ses debug endpoint responds with its own introspection schema
        // (Body.text_part/html_part, values as plain strings) rather than the real SES SendEmail
        // request schema (Body.Text.Data) — confirmed by calling this endpoint after a real
        // SendEmail and inspecting the actual response shape.
        Map<String, Object> messageBody = (Map<String, Object>) matched.get("Body");
        String bodyText = String.valueOf(messageBody.get("text_part"));
        assertThat(bodyText).contains("2 transaction(s)").contains("30000 KRW");

        // Enqueuing again (same month) does not resend — confirms the Level 2 Ledger idempotency
        // left behind by Card.markStatementSent().
        cardStatementScheduler.enqueueMonthlyStatement();

        await().pollDelay(Duration.ofSeconds(3))
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () -> {
                            long count =
                                    cardSentEmailRepository.findAll().stream()
                                            .filter(e -> e.getCardId().equals(cardId))
                                            .count();
                            assertThat(count).isEqualTo(1);
                        });
    }
}

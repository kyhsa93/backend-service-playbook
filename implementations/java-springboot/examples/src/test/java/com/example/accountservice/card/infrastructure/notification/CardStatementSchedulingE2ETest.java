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
 * 월간 카드 사용내역 발송(scheduling.md Feature 2)의 실제 인프라 왕복을 검증하는 E2E 테스트. 진짜 Cron tick(매월 1일 새벽 4시)을 기다리는
 * 대신 {@link CardStatementScheduler#enqueueMonthlyStatement()}를 직접 호출해, Scheduler → task_outbox →
 * TaskOutboxPoller → Task Queue(SQS FIFO, LocalStack) → TaskConsumer →
 * SendCardStatementTaskController → SendCardStatementService → Payment BC 조회(ACL) →
 * NotificationService(SES)로 이어지는 전체 경로를 실제로 태운다. {@code CardSentEmail}이 이 패키지 소속(package-private)이라
 * 이 테스트도 같은 패키지에 둔다(account/infrastructure/notification/ NotificationE2ETest와 동일한 이유).
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

    // LocalStack SES는 검증되지 않은 발신 주소로 sendEmail을 호출하면 MessageRejectedException을
    // 던진다(실제 SES의 sandbox 모드와 동일한 제약) — account/infrastructure/notification/
    // NotificationE2ETest와 동일한 이유로 테스트 시작 전 발신 주소를 미리 검증해둔다.
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
    void 이번_달_카드_사용내역_안내를_적재하면_사용_건수와_합계가_담긴_이메일이_발송된다() throws Exception {
        // 계좌 개설 + 카드 발급 + 결제 2건(합계 30,000원) 실행.
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
        assertThat(sentEmail.getSubject()).contains("카드 사용내역");
        assertThat(sentEmail.getSesMessageId()).isNotBlank();

        // LocalStack SES가 실제로 수신한 메시지 본문에 결제 건수(2건)·합계(30000원)가 정확히 담겼는지
        // 확인한다 — PaymentAdapter(ACL)가 Payment BC의 실제 데이터를 올바르게 집계해 전달했는지의 증거다.
        List<Map<String, Object>> messages = fetchSesMessages();
        Map<String, Object> matched =
                messages.stream()
                        .filter(m -> sentEmail.getSesMessageId().equals(m.get("Id")))
                        .findFirst()
                        .orElseThrow(
                                () -> new AssertionError("localstack SES에서 해당 메시지를 찾을 수 없습니다."));
        // LocalStack의 /_aws/ses 디버그 엔드포인트는 실제 SES SendEmail 요청 스키마(Body.Text.Data)가
        // 아니라 자체 introspection 스키마(Body.text_part/html_part, 값은 문자열 그대로)로 응답한다 —
        // 직접 SendEmail 후 이 엔드포인트를 호출해 실제 응답 형태를 확인했다.
        Map<String, Object> messageBody = (Map<String, Object>) matched.get("Body");
        String bodyText = String.valueOf(messageBody.get("text_part"));
        assertThat(bodyText).contains("2건").contains("30000원");

        // 다시 적재해도(같은 달) 재발송하지 않는다 — Card.markStatementSent()가 남긴 Level 2 Ledger
        // 멱등성 확인.
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

package com.example.accountservice.account.infrastructure.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.accountservice.AccountServiceApplication;
import com.example.accountservice.account.infrastructure.notification.persistence.SentEmail;
import com.example.accountservice.account.infrastructure.notification.persistence.SentEmailRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
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
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.VerifyEmailIdentityRequest;

@Testcontainers
@SuppressWarnings("unchecked")
@SpringBootTest(
        classes = AccountServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NotificationE2ETest {

    private static final String SENDER_EMAIL = "no-reply@backend-service-playbook.example.com";
    private static final String OWNER_ID = "owner-1";
    private static final String RECIPIENT_EMAIL = "owner1@example.com";
    private static final String PASSWORD = "password123!";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static LocalStackContainer localstack =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.0"))
                    .withServices(LocalStackContainer.Service.SES);

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
        // 테스트는 짧은 시간 안에 write API를 기본 limit-for-period(10)보다 많이 호출하므로
        // rate limiting 자체가 아니라 각 엔드포인트 로직을 검증할 수 있도록 테스트 한정으로 넉넉하게 푼다.
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

    @Test
    void 계좌_생성시_SES로_이메일이_발송되고_발송_내역이_DB와_localstack에_기록된다() throws Exception {
        ResponseEntity<Map> response =
                post("/accounts", OWNER_ID, Map.of("currency", "KRW", "email", RECIPIENT_EMAIL));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String accountId = (String) response.getBody().get("accountId");

        SentEmail sentEmail =
                sentEmailRepository
                        .findByAccountIdAndEventType(accountId, "AccountCreated")
                        .orElseThrow(() -> new AssertionError("발송 내역이 저장되지 않았습니다."));
        assertThat(sentEmail.getRecipient()).isEqualTo(RECIPIENT_EMAIL);
        assertThat(sentEmail.getSesMessageId()).isNotBlank();

        List<Map<String, Object>> messages = fetchSesMessages();
        Map<String, Object> matched =
                messages.stream()
                        .filter(m -> sentEmail.getSesMessageId().equals(m.get("Id")))
                        .findFirst()
                        .orElseThrow(
                                () -> new AssertionError("localstack SES에서 해당 메시지를 찾을 수 없습니다."));

        Map<String, Object> destination = (Map<String, Object>) matched.get("Destination");
        assertThat((List<String>) destination.get("ToAddresses")).contains(RECIPIENT_EMAIL);
    }

    @Test
    void 입금시_SES로_이메일이_발송되고_발송_내역이_DB에_기록된다() throws Exception {
        ResponseEntity<Map> createResponse =
                post("/accounts", OWNER_ID, Map.of("currency", "KRW", "email", RECIPIENT_EMAIL));
        String accountId = (String) createResponse.getBody().get("accountId");

        ResponseEntity<Map> depositResponse =
                post("/accounts/" + accountId + "/deposit", OWNER_ID, Map.of("amount", 10000));
        assertThat(depositResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        SentEmail sentEmail =
                sentEmailRepository
                        .findByAccountIdAndEventType(accountId, "MoneyDeposited")
                        .orElseThrow(() -> new AssertionError("발송 내역이 저장되지 않았습니다."));
        assertThat(sentEmail.getRecipient()).isEqualTo(RECIPIENT_EMAIL);

        List<Map<String, Object>> messages = fetchSesMessages();
        boolean matched =
                messages.stream().anyMatch(m -> sentEmail.getSesMessageId().equals(m.get("Id")));
        assertThat(matched).isTrue();
    }
}

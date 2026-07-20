package com.example.accountservice.auth.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.accountservice.AccountServiceApplication;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
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
class AuthControllerE2ETest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.flyway.enabled", () -> "false");
        // 이 테스트는 짧은 시간 안에 write API를 기본 limit-for-period(10)보다 많이 호출하므로
        // rate limiting 자체가 아니라 각 엔드포인트 로직을 검증할 수 있도록 테스트 한정으로 넉넉하게 푼다.
        registry.add(
                "resilience4j.ratelimiter.instances.http-write.limit-for-period", () -> "1000");
    }

    @Autowired private TestRestTemplate restTemplate;

    private static final String PASSWORD = "password123!";

    // TestRestTemplate 기본 요청 팩토리(JDK HttpURLConnection 기반)는 POST 응답이 401이면
    // "cannot retry due to server authentication, in streaming mode" 예외를 던지는 알려진 제약이
    // 있다 — sign-in 실패(401) 응답을 검증하는 이 테스트에서는 이 문제가 없는 httpclient5 기반
    // 팩토리로 바꿔서 피한다(build.gradle의 testImplementation httpclient5 참고).
    @BeforeEach
    void useApacheHttpClientRequestFactory() {
        restTemplate
                .getRestTemplate()
                .setRequestFactory(new HttpComponentsClientHttpRequestFactory());
    }

    private ResponseEntity<Map> signUp(String userId, String password) {
        return restTemplate.postForEntity(
                "/auth/sign-up", Map.of("userId", userId, "password", password), Map.class);
    }

    private ResponseEntity<Map> signIn(String userId, String password) {
        return restTemplate.postForEntity(
                "/auth/sign-in", Map.of("userId", userId, "password", password), Map.class);
    }

    @Test
    void sign_up_후_sign_in하면_201과_액세스_토큰을_반환한다() {
        ResponseEntity<Map> signUpResponse = signUp("owner-1", PASSWORD);
        assertThat(signUpResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map> signInResponse = signIn("owner-1", PASSWORD);
        assertThat(signInResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(signInResponse.getBody().get("accessToken")).isNotNull();
    }

    @Test
    void sign_in_시_비밀번호가_틀리면_401과_INVALID_CREDENTIALS를_반환한다() {
        signUp("owner-2", PASSWORD);

        ResponseEntity<Map> response = signIn("owner-2", "wrong-password");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("code")).isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void sign_in_시_존재하지_않는_아이디면_401과_INVALID_CREDENTIALS를_반환한다() {
        ResponseEntity<Map> response = signIn("no-such-user", PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("code")).isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void sign_up_시_이미_사용_중인_아이디면_400과_USER_ID_ALREADY_EXISTS를_반환한다() {
        signUp("owner-3", PASSWORD);

        ResponseEntity<Map> response = signUp("owner-3", "another-password1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("USER_ID_ALREADY_EXISTS");
    }

    @Test
    void sign_up_시_비밀번호가_8자_미만이면_400을_반환한다() {
        ResponseEntity<Map> response = signUp("owner-4", "short");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}

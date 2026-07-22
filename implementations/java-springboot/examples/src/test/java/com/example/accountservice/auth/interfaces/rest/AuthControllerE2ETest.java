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
        // This test calls the write API more times in a short window than the default
        // limit-for-period (10), so we relax it generously for tests only, so that each endpoint's
        // logic is verified rather than rate limiting itself.
        registry.add(
                "resilience4j.ratelimiter.instances.http-write.limit-for-period", () -> "1000");
    }

    @Autowired private TestRestTemplate restTemplate;

    private static final String PASSWORD = "password123!";

    // TestRestTemplate's default request factory (based on JDK HttpURLConnection) has a known
    // limitation where it throws a "cannot retry due to server authentication, in streaming mode"
    // exception when a POST response is 401 — since this test verifies sign-in failure (401)
    // responses, we avoid the issue by switching to an httpclient5-based factory instead (see the
    // testImplementation httpclient5 dependency in build.gradle).
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

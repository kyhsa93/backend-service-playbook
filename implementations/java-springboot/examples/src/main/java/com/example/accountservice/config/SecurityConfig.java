package com.example.accountservice.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import java.nio.charset.StandardCharsets;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtDecoder jwtDecoder)
            throws Exception {
        return http.csrf(CsrfConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        auth ->
                                auth
                                        // /error: when Bean Validation fails on an endpoint that's
                                        // callable without authentication (e.g. /auth/sign-up),
                                        // the servlet container re-dispatches to /error. Spring
                                        // Boot re-applies the Security filter chain to this
                                        // re-dispatch by default, so unless /error is included in
                                        // permitAll, a response that should not have been 401
                                        // (e.g. 400 VALIDATION_FAILED) gets turned into a 401.
                                        .requestMatchers(
                                                "/health/**",
                                                "/actuator/health/**",
                                                // A Prometheus scraper hits this endpoint directly
                                                // (no app-issued JWT) — same rationale as the
                                                // health probes above (observability.md).
                                                "/actuator/prometheus",
                                                "/error",
                                                "/auth/sign-in",
                                                "/auth/sign-up",
                                                // The OpenAPI/Swagger docs must be reachable
                                                // without
                                                // a token — a client that doesn't have one yet
                                                // still
                                                // needs to be able to read the docs to learn how to
                                                // sign in (see bootstrap.md, api-response.md).
                                                "/v3/api-docs/**",
                                                "/swagger-ui.html",
                                                "/swagger-ui/**")
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.decoder(jwtDecoder)))
                .build();
    }

    @Bean
    public SecretKeySpec jwtSecretKey(JwtProperties jwtProperties) {
        return new SecretKeySpec(
                jwtProperties.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean
    public JwtEncoder jwtEncoder(SecretKeySpec jwtSecretKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(jwtSecretKey));
    }

    @Bean
    public JwtDecoder jwtDecoder(SecretKeySpec jwtSecretKey) {
        return NimbusJwtDecoder.withSecretKey(jwtSecretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }
}

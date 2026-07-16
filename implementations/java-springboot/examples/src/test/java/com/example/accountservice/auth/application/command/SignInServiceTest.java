package com.example.accountservice.auth.application.command;

import com.example.accountservice.auth.application.query.CredentialQuery;
import com.example.accountservice.auth.application.service.PasswordHasher;
import com.example.accountservice.auth.domain.AuthException;
import com.example.accountservice.auth.domain.Credential;
import com.example.accountservice.auth.domain.CredentialFindQuery;
import com.example.accountservice.auth.domain.CredentialsWithCount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SignInServiceTest {

    @Mock
    private CredentialQuery credentialQuery;

    @Mock
    private PasswordHasher passwordHasher;

    @Mock
    private JwtEncoder jwtEncoder;

    private SignInService service;

    @BeforeEach
    void setUp() {
        service = new SignInService(credentialQuery, passwordHasher, jwtEncoder);
    }

    @Test
    void 아이디와_비밀번호가_일치하면_액세스_토큰을_발급한다() {
        Credential credential = Credential.create("owner-1", "hashed-password");
        when(credentialQuery.findCredentials(new CredentialFindQuery(0, 1, "owner-1")))
                .thenReturn(new CredentialsWithCount(List.of(credential), 1));
        when(passwordHasher.verify("plain-password", "hashed-password")).thenReturn(true);
        Jwt jwt = Jwt.withTokenValue("access-token")
                .header("alg", "HS256")
                .claim("sub", "owner-1")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        when(jwtEncoder.encode(any(JwtEncoderParameters.class))).thenReturn(jwt);

        SignInResult result = service.signIn(new SignInCommand("owner-1", "plain-password"));

        assertThat(result.accessToken()).isEqualTo("access-token");
    }

    @Test
    void 존재하지_않는_아이디면_예외를_던진다() {
        when(credentialQuery.findCredentials(new CredentialFindQuery(0, 1, "no-such-user")))
                .thenReturn(new CredentialsWithCount(List.of(), 0));

        assertThatThrownBy(() -> service.signIn(new SignInCommand("no-such-user", "plain-password")))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).code())
                .isEqualTo(AuthException.ErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    void 비밀번호가_틀리면_예외를_던진다() {
        Credential credential = Credential.create("owner-1", "hashed-password");
        when(credentialQuery.findCredentials(new CredentialFindQuery(0, 1, "owner-1")))
                .thenReturn(new CredentialsWithCount(List.of(credential), 1));
        when(passwordHasher.verify("wrong-password", "hashed-password")).thenReturn(false);

        assertThatThrownBy(() -> service.signIn(new SignInCommand("owner-1", "wrong-password")))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).code())
                .isEqualTo(AuthException.ErrorCode.INVALID_CREDENTIALS);
    }
}

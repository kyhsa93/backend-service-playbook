package com.example.accountservice.auth.application.command;

import com.example.accountservice.auth.application.query.CredentialQuery;
import com.example.accountservice.auth.application.service.PasswordHasher;
import com.example.accountservice.auth.domain.AuthException;
import com.example.accountservice.auth.domain.Credential;
import com.example.accountservice.auth.domain.CredentialFindQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
@RequiredArgsConstructor
public class SignInService {

    private final CredentialQuery credentialQuery;
    private final PasswordHasher passwordHasher;
    private final JwtEncoder jwtEncoder;

    // 아이디 미존재와 비밀번호 불일치를 동일한 에러 코드/메시지(INVALID_CREDENTIALS)로 응답한다 —
    // 둘을 구분해서 응답하면 공격자가 존재하는 아이디를 추측할 수 있다(user enumeration, authentication.md 참고).
    public SignInResult signIn(SignInCommand command) {
        Credential credential = credentialQuery
                .findCredentials(new CredentialFindQuery(0, 1, command.userId()))
                .credentials().stream().findFirst()
                .orElseThrow(() -> new AuthException(
                        AuthException.ErrorCode.INVALID_CREDENTIALS, "아이디 또는 비밀번호가 올바르지 않습니다."));

        if (!passwordHasher.verify(command.password(), credential.getPasswordHash())) {
            throw new AuthException(
                    AuthException.ErrorCode.INVALID_CREDENTIALS, "아이디 또는 비밀번호가 올바르지 않습니다.");
        }

        Instant now = Instant.now();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("account-service")
                .issuedAt(now)
                .expiresAt(now.plus(1, ChronoUnit.HOURS))
                .subject(credential.getUserId())
                .build();

        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new SignInResult(token);
    }
}

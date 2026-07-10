package com.example.accountservice.auth.application.command;

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

    private final JwtEncoder jwtEncoder;

    // 이 저장소에는 별도의 User/자격증명 저장소가 없다 — 자격증명 검증 없이 주어진 userId로
    // 토큰을 발급한다(nestjs/go 구현과 동일한 단순화). payload는 최소한(userId/subject)만 담는다.
    public SignInResult signIn(SignInCommand command) {
        Instant now = Instant.now();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("account-service")
                .issuedAt(now)
                .expiresAt(now.plus(1, ChronoUnit.HOURS))
                .subject(command.userId())
                .build();

        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new SignInResult(token);
    }
}

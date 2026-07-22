package com.example.accountservice.auth.application.command;

import com.example.accountservice.auth.application.query.CredentialQuery;
import com.example.accountservice.auth.application.service.PasswordHasher;
import com.example.accountservice.auth.domain.AuthException;
import com.example.accountservice.auth.domain.Credential;
import com.example.accountservice.auth.domain.CredentialFindQuery;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SignInService {

    private final CredentialQuery credentialQuery;
    private final PasswordHasher passwordHasher;
    private final JwtEncoder jwtEncoder;

    // A nonexistent ID and an incorrect password are both answered with the same error code/message
    // (INVALID_CREDENTIALS) — responding differently for each would let an attacker guess which IDs
    // exist (user enumeration, see authentication.md).
    public SignInResult signIn(SignInCommand command) {
        Credential credential =
                credentialQuery
                        .findCredentials(new CredentialFindQuery(0, 1, command.userId()))
                        .credentials()
                        .stream()
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new AuthException(
                                                AuthException.ErrorCode.INVALID_CREDENTIALS,
                                                "Incorrect ID or password."));

        if (!passwordHasher.verify(command.password(), credential.getPasswordHash())) {
            throw new AuthException(
                    AuthException.ErrorCode.INVALID_CREDENTIALS, "Incorrect ID or password.");
        }

        Instant now = Instant.now();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        JwtClaimsSet claims =
                JwtClaimsSet.builder()
                        .issuer("account-service")
                        .issuedAt(now)
                        .expiresAt(now.plus(1, ChronoUnit.HOURS))
                        .subject(credential.getUserId())
                        .build();

        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new SignInResult(token);
    }
}

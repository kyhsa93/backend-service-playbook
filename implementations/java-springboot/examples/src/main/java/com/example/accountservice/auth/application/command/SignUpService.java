package com.example.accountservice.auth.application.command;

import com.example.accountservice.auth.application.query.CredentialQuery;
import com.example.accountservice.auth.application.service.PasswordHasher;
import com.example.accountservice.auth.domain.AuthException;
import com.example.accountservice.auth.domain.Credential;
import com.example.accountservice.auth.domain.CredentialFindQuery;
import com.example.accountservice.auth.domain.CredentialRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SignUpService {

    private final CredentialQuery credentialQuery;
    private final CredentialRepository credentialRepository;
    private final PasswordHasher passwordHasher;

    public void signUp(SignUpCommand command) {
        boolean exists =
                !credentialQuery
                        .findCredentials(new CredentialFindQuery(0, 1, command.userId()))
                        .credentials()
                        .isEmpty();
        if (exists) {
            throw new AuthException(
                    AuthException.ErrorCode.USER_ID_ALREADY_EXISTS, "This ID is already in use.");
        }

        String passwordHash = passwordHasher.hash(command.password());
        Credential credential = Credential.create(command.userId(), passwordHash);
        credentialRepository.saveCredential(credential);
    }
}

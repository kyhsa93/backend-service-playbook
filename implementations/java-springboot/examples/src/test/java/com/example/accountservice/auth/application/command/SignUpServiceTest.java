package com.example.accountservice.auth.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.accountservice.auth.application.query.CredentialQuery;
import com.example.accountservice.auth.application.service.PasswordHasher;
import com.example.accountservice.auth.domain.AuthException;
import com.example.accountservice.auth.domain.Credential;
import com.example.accountservice.auth.domain.CredentialFindQuery;
import com.example.accountservice.auth.domain.CredentialRepository;
import com.example.accountservice.auth.domain.CredentialsWithCount;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SignUpServiceTest {

    @Mock private CredentialQuery credentialQuery;

    @Mock private CredentialRepository credentialRepository;

    @Mock private PasswordHasher passwordHasher;

    private SignUpService service;

    @BeforeEach
    void setUp() {
        service = new SignUpService(credentialQuery, credentialRepository, passwordHasher);
    }

    @Test
    void 신규_아이디면_비밀번호를_해싱해서_저장한다() {
        when(credentialQuery.findCredentials(new CredentialFindQuery(0, 1, "owner-1")))
                .thenReturn(new CredentialsWithCount(List.of(), 0));
        when(passwordHasher.hash("plain-password")).thenReturn("hashed-password");

        service.signUp(new SignUpCommand("owner-1", "plain-password"));

        ArgumentCaptor<Credential> captor = ArgumentCaptor.forClass(Credential.class);
        verify(credentialRepository).saveCredential(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo("owner-1");
        assertThat(captor.getValue().getPasswordHash()).isEqualTo("hashed-password");
    }

    @Test
    void 이미_존재하는_아이디면_예외를_던지고_저장하지_않는다() {
        Credential existing = Credential.create("owner-1", "existing-hash");
        when(credentialQuery.findCredentials(new CredentialFindQuery(0, 1, "owner-1")))
                .thenReturn(new CredentialsWithCount(List.of(existing), 1));

        assertThatThrownBy(() -> service.signUp(new SignUpCommand("owner-1", "plain-password")))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).code())
                .isEqualTo(AuthException.ErrorCode.USER_ID_ALREADY_EXISTS);
        verify(credentialRepository, never()).saveCredential(any());
    }
}

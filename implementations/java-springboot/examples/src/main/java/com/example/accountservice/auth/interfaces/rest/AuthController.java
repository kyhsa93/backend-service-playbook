package com.example.accountservice.auth.interfaces.rest;

import static net.logstash.logback.argument.StructuredArguments.kv;

import com.example.accountservice.account.interfaces.rest.ErrorResponse;
import com.example.accountservice.auth.application.command.SignInCommand;
import com.example.accountservice.auth.application.command.SignInResult;
import com.example.accountservice.auth.application.command.SignInService;
import com.example.accountservice.auth.application.command.SignUpCommand;
import com.example.accountservice.auth.application.command.SignUpService;
import com.example.accountservice.auth.domain.AuthException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final SignUpService signUpService;
    private final SignInService signInService;

    @PostMapping("/sign-up")
    @ResponseStatus(HttpStatus.CREATED)
    public void signUp(@Valid @RequestBody SignUpRequest request) {
        signUpService.signUp(new SignUpCommand(request.userId(), request.password()));
    }

    @PostMapping("/sign-in")
    public SignInResult signIn(@Valid @RequestBody SignInRequest request) {
        return signInService.signIn(new SignInCommand(request.userId(), request.password()));
    }

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<ErrorResponse> handleAuthException(AuthException e) {
        HttpStatus status =
                e.code() == AuthException.ErrorCode.INVALID_CREDENTIALS
                        ? HttpStatus.UNAUTHORIZED
                        : HttpStatus.BAD_REQUEST;
        log.warn(
                "Authentication request failed",
                kv("code", e.code()),
                kv("message", e.getMessage()));
        return ResponseEntity.status(status)
                .body(ErrorResponse.of(status, e.code().name(), e.getMessage()));
    }
}

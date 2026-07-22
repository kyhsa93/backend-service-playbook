package com.example.accountservice.auth.interfaces.rest;

import static net.logstash.logback.argument.StructuredArguments.kv;

import com.example.accountservice.account.interfaces.rest.ErrorResponse;
import com.example.accountservice.auth.application.command.SignInCommand;
import com.example.accountservice.auth.application.command.SignInResult;
import com.example.accountservice.auth.application.command.SignInService;
import com.example.accountservice.auth.application.command.SignUpCommand;
import com.example.accountservice.auth.application.command.SignUpService;
import com.example.accountservice.auth.domain.AuthException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final SignUpService signUpService;
    private final SignInService signInService;

    @PostMapping("/sign-up")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Register a new user",
            description =
                    "Creates a new credential (userId + password) that can then be used to sign in.")
    @ApiResponse(responseCode = "201", description = "The user was registered.")
    @ApiResponse(
            responseCode = "400",
            description =
                    "One of: the userId is already in use (`USER_ID_ALREADY_EXISTS`), or request"
                            + " validation failed (`VALIDATION_FAILED`) — e.g. a password shorter"
                            + " than 8 characters.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public void signUp(@Valid @RequestBody SignUpRequest request) {
        signUpService.signUp(new SignUpCommand(request.userId(), request.password()));
    }

    @PostMapping("/sign-in")
    @Operation(
            summary = "Sign in",
            description =
                    "Verifies the userId and password, and issues a bearer access token to use as"
                            + " `Authorization: Bearer <token>` on every other endpoint.")
    @ApiResponse(
            responseCode = "200",
            description = "The credentials were valid; an access token was issued.",
            content = @Content(schema = @Schema(implementation = SignInResult.class)))
    @ApiResponse(
            responseCode = "400",
            description =
                    "Request validation failed (`VALIDATION_FAILED`) — e.g. a missing userId or password.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(
            responseCode = "401",
            description =
                    "The userId or password is incorrect (`INVALID_CREDENTIALS`). The same message"
                            + " and code are returned whether the userId doesn't exist or the"
                            + " password is wrong, to avoid leaking which one was wrong.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
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

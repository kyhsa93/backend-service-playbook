package com.example.accountservice.auth.interfaces.rest

import com.example.accountservice.account.interfaces.rest.ErrorResponse
import com.example.accountservice.auth.application.command.SignInCommand
import com.example.accountservice.auth.application.command.SignInService
import com.example.accountservice.auth.application.command.SignUpCommand
import com.example.accountservice.auth.application.command.SignUpService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

data class SignUpRequest(
    @field:NotBlank
    @field:Schema(description = "The desired login user ID. Must not already be in use.", example = "jane.doe")
    val userId: String,
    @field:Size(min = 8)
    @field:Schema(description = "The account password. Must be at least 8 characters.", example = "correct-horse-battery-staple")
    val password: String,
)

data class SignInRequest(
    @field:NotBlank
    @field:Schema(description = "The login user ID.", example = "jane.doe")
    val userId: String,
    @field:NotBlank
    @field:Schema(description = "The account password.", example = "correct-horse-battery-staple")
    val password: String,
)

data class SignInResponse(
    @field:Schema(description = "A signed JWT bearer token. Send it as `Authorization: Bearer <token>` on every authenticated request.")
    val accessToken: String,
)

@RestController
@RequestMapping("/auth")
@Tag(name = "Auth")
class AuthController(
    private val signUpService: SignUpService,
    private val signInService: SignInService,
) {
    @PostMapping("/sign-up")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
        summary = "Register a new user",
        description = "Creates a new login credential (userId + password). The password is hashed before storage, never kept in plaintext.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "The user was registered."),
        ApiResponse(
            responseCode = "400",
            description =
                "One of: userId already in use (`USER_ID_ALREADY_EXISTS`), or validation failed " +
                    "(`VALIDATION_FAILED`) — e.g. password shorter than 8 characters.",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    fun signUp(
        @Valid @RequestBody request: SignUpRequest,
    ) {
        signUpService.signUp(SignUpCommand(request.userId, request.password))
    }

    @PostMapping("/sign-in")
    @Operation(
        summary = "Sign in and obtain a bearer token",
        description = "Verifies the userId/password and returns a signed JWT to use as a bearer token on subsequent requests.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Sign-in succeeded."),
        ApiResponse(
            responseCode = "400",
            description = "Request validation failed (`VALIDATION_FAILED`) — e.g. a missing userId or password.",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "401",
            description =
                "The userId does not exist, or the password does not match (`INVALID_CREDENTIALS`) — the same error " +
                    "either way so a client cannot enumerate valid user IDs.",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    fun signIn(
        @Valid @RequestBody request: SignInRequest,
    ): SignInResponse = SignInResponse(signInService.signIn(SignInCommand(request.userId, request.password)))
}

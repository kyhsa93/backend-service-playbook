package com.example.accountservice.auth.interfaces.rest

import com.example.accountservice.auth.application.command.SignInCommand
import com.example.accountservice.auth.application.command.SignInService
import com.example.accountservice.auth.application.command.SignUpCommand
import com.example.accountservice.auth.application.command.SignUpService
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
    @field:NotBlank val userId: String,
    @field:Size(min = 8) val password: String,
)

data class SignInRequest(
    @field:NotBlank val userId: String,
    @field:NotBlank val password: String,
)

data class SignInResponse(val accessToken: String)

@RestController
@RequestMapping("/auth")
class AuthController(
    private val signUpService: SignUpService,
    private val signInService: SignInService,
) {

    @PostMapping("/sign-up")
    @ResponseStatus(HttpStatus.CREATED)
    fun signUp(@Valid @RequestBody request: SignUpRequest) {
        signUpService.signUp(SignUpCommand(request.userId, request.password))
    }

    @PostMapping("/sign-in")
    fun signIn(@Valid @RequestBody request: SignInRequest): SignInResponse =
        SignInResponse(signInService.signIn(SignInCommand(request.userId, request.password)))
}

package com.example.accountservice.auth.interfaces.rest

import com.example.accountservice.auth.application.AuthService
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class SignInRequest(@field:NotBlank val userId: String)

data class SignInResponse(val accessToken: String)

@RestController
@RequestMapping("/auth")
class AuthController(private val authService: AuthService) {

    @PostMapping("/sign-in")
    fun signIn(@Valid @RequestBody request: SignInRequest): SignInResponse =
        SignInResponse(authService.sign(request.userId))
}

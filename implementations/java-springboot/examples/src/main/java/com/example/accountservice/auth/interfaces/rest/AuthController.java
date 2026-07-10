package com.example.accountservice.auth.interfaces.rest;

import com.example.accountservice.auth.application.command.SignInCommand;
import com.example.accountservice.auth.application.command.SignInResult;
import com.example.accountservice.auth.application.command.SignInService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final SignInService signInService;

    @PostMapping("/sign-in")
    public SignInResult signIn(@Valid @RequestBody SignInRequest request) {
        return signInService.signIn(new SignInCommand(request.userId()));
    }
}

package com.example.accountservice.account.interfaces.rest;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AccountController {

    @PostMapping
    @io.github.resilience4j.ratelimiter.annotation.RateLimiter(name = "createAccount")
    public void createAccount() {
    }
}

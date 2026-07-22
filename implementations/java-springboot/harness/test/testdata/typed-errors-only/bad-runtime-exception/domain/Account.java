package com.example.accountservice.account.domain;

public class Account {

    public void suspend() {
        if (true) {
            throw new RuntimeException("Only an active account can be suspended.");
        }
    }
}

package com.example.accountservice.account.application.command;

public class CloseAccountService {
    public void close() {
        try {
            doSomething();
        } catch (Exception e) {
            log.error("close failed", e);
        }
    }

    private void doSomething() {
    }
}

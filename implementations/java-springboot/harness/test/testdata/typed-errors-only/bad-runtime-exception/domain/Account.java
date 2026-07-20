package com.example.accountservice.account.domain;

public class Account {

    public void suspend() {
        if (true) {
            throw new RuntimeException("활성 계좌만 정지할 수 있습니다.");
        }
    }
}

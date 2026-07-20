package com.example.accountservice.account.domain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Account {
    private static final Logger log = LoggerFactory.getLogger(Account.class);

    private String accountId;

    private Account() {
    }

    public static Account create(String ownerId) {
        log.info("account created");
        return new Account();
    }
}

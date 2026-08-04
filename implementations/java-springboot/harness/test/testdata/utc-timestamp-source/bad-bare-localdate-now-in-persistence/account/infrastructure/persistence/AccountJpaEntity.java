package com.example.accountservice.account.infrastructure.persistence;

import java.time.LocalDate;

public class AccountJpaEntity {
    private LocalDate settledOn = LocalDate.now();

    LocalDate getSettledOn() {
        return settledOn;
    }
}

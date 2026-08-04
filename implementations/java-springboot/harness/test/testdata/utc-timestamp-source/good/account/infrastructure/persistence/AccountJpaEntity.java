package com.example.accountservice.account.infrastructure.persistence;

import com.example.accountservice.common.UtcClock;
import java.time.LocalDateTime;

public class AccountJpaEntity {
    private LocalDateTime touchedAt = UtcClock.now();

    LocalDateTime getTouchedAt() {
        return touchedAt;
    }
}

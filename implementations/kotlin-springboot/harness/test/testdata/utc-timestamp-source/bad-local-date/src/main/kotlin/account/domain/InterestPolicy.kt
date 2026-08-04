package com.example.accountservice.account.domain

import java.time.LocalDate

// A date-only reading is the sharper case: near midnight LocalDate.now() names a different day
// depending on the host zone, so the idempotency key below points at the wrong day.
class InterestPolicy {
    fun payDate(): LocalDate = LocalDate.now()
}

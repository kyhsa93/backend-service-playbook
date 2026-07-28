package com.example.accountservice.account.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class TransactionTest {

    @Test
    void categorize_returns_a_new_instance_with_the_category_set_and_every_other_field_unchanged() {
        Transaction transaction =
                Transaction.reconstitute(
                        "transaction-1",
                        "account-1",
                        TransactionType.WITHDRAWAL,
                        new Money(5500, "KRW"),
                        null,
                        "Starbucks Gangnam",
                        null,
                        LocalDateTime.of(2026, 7, 28, 0, 0));

        Transaction categorized = transaction.categorize(TransactionCategory.FOOD);

        assertThat(categorized.getCategory()).isEqualTo(TransactionCategory.FOOD);
        assertThat(categorized).isNotSameAs(transaction);
        assertThat(transaction.getCategory()).isNull();
        assertThat(categorized.getTransactionId()).isEqualTo(transaction.getTransactionId());
        assertThat(categorized.getMerchantName()).isEqualTo(transaction.getMerchantName());
        assertThat(categorized.getAmount()).isEqualTo(transaction.getAmount());
        assertThat(categorized.getCreatedAt()).isEqualTo(transaction.getCreatedAt());
    }
}

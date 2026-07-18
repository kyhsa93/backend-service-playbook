package com.example.accountservice.account.domain;

import com.example.accountservice.common.IdGenerator;
import java.time.LocalDateTime;

/**
 * Account Aggregate의 하위 Entity — 순수 도메인 객체. 어떤 프레임워크/ORM에도 의존하지 않는다. 영속성 매핑은
 * infrastructure/persistence/TransactionJpaEntity + TransactionMapper가 전담한다.
 */
public class Transaction {

    private String transactionId;
    private String accountId;
    private TransactionType type;
    private Money amount;
    // 외부 BC(Payment)의 Integration Event 반응으로 발생한 거래를 다른 BC의 Aggregate ID(paymentId/refundId)로
    // 상관관계 지을 수 있게 하는 선택 필드다. 사용자가 직접 요청한 입금/출금에는 없다(null) — Payment 반응
    // Command(WithdrawByPaymentService/
    // DepositByPaymentService)에서만 채워지며, at-least-once 재수신 시 이 값 + type으로 중복 처리를 막는 Level 2 Ledger
    // 키로
    // 쓰인다(domain-events.md의 "이벤트 핸들러 멱등성" 참고).
    private String referenceId;
    private LocalDateTime createdAt;

    private Transaction() {}

    static Transaction create(String accountId, TransactionType type, Money amount) {
        return create(accountId, type, amount, null);
    }

    static Transaction create(
            String accountId, TransactionType type, Money amount, String referenceId) {
        Transaction transaction = new Transaction();
        transaction.transactionId = IdGenerator.generate();
        transaction.accountId = accountId;
        transaction.type = type;
        transaction.amount = amount;
        transaction.referenceId = referenceId;
        transaction.createdAt = LocalDateTime.now();
        return transaction;
    }

    /** Repository 구현체가 영속 데이터(JPA 엔티티 등)로부터 Transaction을 복원할 때 사용한다. */
    public static Transaction reconstitute(
            String transactionId,
            String accountId,
            TransactionType type,
            Money amount,
            String referenceId,
            LocalDateTime createdAt) {
        Transaction transaction = new Transaction();
        transaction.transactionId = transactionId;
        transaction.accountId = accountId;
        transaction.type = type;
        transaction.amount = amount;
        transaction.referenceId = referenceId;
        transaction.createdAt = createdAt;
        return transaction;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public String getAccountId() {
        return accountId;
    }

    public TransactionType getType() {
        return type;
    }

    public Money getAmount() {
        return amount;
    }

    public String getReferenceId() {
        return referenceId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}

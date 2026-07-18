package com.example.accountservice.payment.domain;

import com.example.accountservice.common.IdGenerator;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Payment Aggregate Root — 순수 도메인 객체. 어떤 프레임워크/ORM에도 의존하지 않는다. 영속성 매핑은
 * infrastructure/persistence/PaymentJpaEntity + PaymentMapper가 전담한다(account/domain/Account.java와
 * 동일한 domain/JPA 분리 구조).
 *
 * <p>{@code cardId}로 어느 카드를 썼는지, {@code accountId}로 어느 계좌가 차감 대상인지 참조만 하고(BC 경계를 넘는 FK 없음) 카드·계좌의
 * 실제 상태·잔액 판단은 Application 레이어가 {@code CardAdapter}/{@code AccountAdapter}(ACL)로 동기 조회해 이
 * Aggregate를 생성하기 전에 끝낸다 — Payment 자신은 "카드가 활성인지", "잔액이 충분한지"를 알지 못한다.
 */
public class Payment {

    private String paymentId;
    private String cardId;
    private String accountId;
    private String ownerId;
    private long amount;
    private PaymentStatus status;
    private LocalDateTime createdAt;

    private final List<Object> domainEvents = new ArrayList<>();

    private Payment() {}

    /**
     * Repository 구현체가 영속 데이터로부터 Payment를 복원할 때 사용한다. 과거에 커밋된 상태를 그대로 재구성하며 Domain Event는 만들지 않는다.
     */
    public static Payment reconstitute(
            String paymentId,
            String cardId,
            String accountId,
            String ownerId,
            long amount,
            PaymentStatus status,
            LocalDateTime createdAt) {
        Payment payment = new Payment();
        payment.paymentId = paymentId;
        payment.cardId = cardId;
        payment.accountId = accountId;
        payment.ownerId = ownerId;
        payment.amount = amount;
        payment.status = status;
        payment.createdAt = createdAt;
        return payment;
    }

    /**
     * 카드 활성 여부·계좌 잔액 충분 여부는 이미 Application 레이어의 동기 Adapter 호출로 판정이 끝난 뒤 호출되는 순수 생성 팩토리다 —
     * PENDING으로만 만들고 이벤트는 없다.
     */
    public static Payment create(String cardId, String accountId, String ownerId, long amount) {
        Payment payment = new Payment();
        payment.paymentId = IdGenerator.generate();
        payment.cardId = cardId;
        payment.accountId = accountId;
        payment.ownerId = ownerId;
        payment.amount = amount;
        payment.status = PaymentStatus.PENDING;
        payment.createdAt = LocalDateTime.now();
        return payment;
    }

    public void complete() {
        if (this.status != PaymentStatus.PENDING) {
            throw new PaymentException(
                    PaymentException.ErrorCode.PAYMENT_COMPLETE_REQUIRES_PENDING_PAYMENT,
                    "결제 대기 상태에서만 완료 처리할 수 있습니다.");
        }
        this.status = PaymentStatus.COMPLETED;
        this.domainEvents.add(
                new PaymentCompletedEvent(
                        this.paymentId,
                        this.cardId,
                        this.accountId,
                        this.ownerId,
                        this.amount,
                        LocalDateTime.now()));
    }

    /**
     * 현재 {@code CreatePaymentService}는 통과 여부를 생성 이전에 동기 Adapter로 판정하므로 Payment Aggregate가 PENDING으로
     * 만들어진 뒤 실패하는 경로는 없다. 다만 향후 결제 게이트웨이 콜백처럼 비동기로 실패가 도착하는 시나리오를 대비해 상태 전이 자체는 Aggregate가 갖고
     * 있는다(Domain 단위 테스트로 검증) — 현재 어떤 Command도 이 메서드를 호출하지 않는다.
     */
    public void fail(String reason) {
        if (this.status != PaymentStatus.PENDING) {
            throw new PaymentException(
                    PaymentException.ErrorCode.PAYMENT_FAIL_REQUIRES_PENDING_PAYMENT,
                    "결제 대기 상태에서만 실패 처리할 수 있습니다.");
        }
        this.status = PaymentStatus.FAILED;
    }

    /** 결제취소는 이미 확정된(COMPLETED) 결제를 되돌리는 것이므로 COMPLETED에서만 가능하다. */
    public void cancel(String reason) {
        if (this.status != PaymentStatus.COMPLETED) {
            throw new PaymentException(
                    PaymentException.ErrorCode.PAYMENT_CANCEL_REQUIRES_COMPLETED_PAYMENT,
                    "완료된 결제만 취소할 수 있습니다.");
        }
        this.status = PaymentStatus.CANCELLED;
        this.domainEvents.add(
                new PaymentCancelledEvent(
                        this.paymentId,
                        this.accountId,
                        this.ownerId,
                        this.amount,
                        reason,
                        LocalDateTime.now()));
    }

    public List<Object> pullDomainEvents() {
        List<Object> events = new ArrayList<>(this.domainEvents);
        this.domainEvents.clear();
        return events;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public String getCardId() {
        return cardId;
    }

    public String getAccountId() {
        return accountId;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public long getAmount() {
        return amount;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}

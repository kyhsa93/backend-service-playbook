package com.example.accountservice.payment.domain

import com.example.accountservice.common.generateId
import java.time.LocalDateTime

/**
 * Payment Aggregate Root — 어떤 프레임워크/ORM에도 의존하지 않는 순수 Kotlin 객체
 * (account/domain/Account.kt, card/domain/Card.kt와 동일한 domain/JPA 분리 구조).
 *
 * `cardId`/`accountId`로 어느 카드·계좌가 관련되었는지 참조만 한다(BC 경계를 넘는 FK 없음).
 * 카드가 활성 상태인지, 연결 계좌 잔액이 충분한지는 Payment 스스로 판단할 수 없다 — Application
 * 레이어가 [com.example.accountservice.payment.application.adapter.CardAdapter]/
 * [com.example.accountservice.payment.application.adapter.AccountAdapter](ACL)로 동기 조회해 이미
 * 판정을 끝낸 뒤 [create]를 호출한다.
 */
class Payment private constructor() {
    var paymentId: String = ""
        private set

    var cardId: String = ""
        private set

    var accountId: String = ""
        private set

    var ownerId: String = ""
        private set

    var amount: Long = 0
        private set

    var status: PaymentStatus = PaymentStatus.PENDING
        private set

    var createdAt: LocalDateTime = LocalDateTime.now()
        private set

    private val domainEvents: MutableList<PaymentDomainEvent> = mutableListOf()

    companion object {
        /**
         * 카드 활성 여부·계좌 잔액 충분 여부는 이미 Application 레이어의 동기 Adapter 호출로 판정이
         * 끝난 뒤 호출되는 순수 생성 팩토리다 — PENDING으로만 만들고 이벤트는 없다.
         */
        fun create(
            cardId: String,
            accountId: String,
            ownerId: String,
            amount: Long,
        ): Payment =
            Payment().apply {
                this.paymentId = generateId()
                this.cardId = cardId
                this.accountId = accountId
                this.ownerId = ownerId
                this.amount = amount
                this.status = PaymentStatus.PENDING
                this.createdAt = LocalDateTime.now()
            }

        /**
         * Repository 구현체가 영속 데이터(JPA 엔티티 등)로부터 Payment를 복원할 때 사용한다.
         * create()와 달리 도메인 이벤트를 생성하지 않는다.
         */
        fun reconstitute(
            paymentId: String,
            cardId: String,
            accountId: String,
            ownerId: String,
            amount: Long,
            status: PaymentStatus,
            createdAt: LocalDateTime,
        ): Payment =
            Payment().apply {
                this.paymentId = paymentId
                this.cardId = cardId
                this.accountId = accountId
                this.ownerId = ownerId
                this.amount = amount
                this.status = status
                this.createdAt = createdAt
            }
    }

    fun complete() {
        if (status != PaymentStatus.PENDING) throw PaymentCompleteRequiresPendingPaymentException()
        status = PaymentStatus.COMPLETED
        domainEvents += PaymentCompletedEvent(paymentId, cardId, accountId, ownerId, amount, LocalDateTime.now())
    }

    /**
     * 현재 [com.example.accountservice.payment.application.command.CreatePaymentService]는 결제
     * 가능 여부를 Payment 생성 전에 동기 Adapter로 판정하므로, PENDING으로 만들어진 뒤 실패하는
     * 경로는 없다. 다만 향후 결제 게이트웨이 콜백처럼 비동기로 실패가 도착하는 시나리오를 대비해
     * 상태 전이 자체는 Aggregate가 갖고 있는다(Domain 단위 테스트로 검증) — 아직 어떤 Command도
     * 호출하지 않는다.
     */
    fun fail(reason: String) {
        if (status != PaymentStatus.PENDING) throw PaymentFailRequiresPendingPaymentException()
        status = PaymentStatus.FAILED
        // reason은 현재 Payment 상태에 별도로 보관하지 않는다(nestjs 레퍼런스와 동일) — 상태 전이
        // 자체가 목적인 미연결 도메인 메서드다.
    }

    /** 결제취소는 이미 확정된(COMPLETED) 결제를 되돌리는 것이므로 COMPLETED에서만 가능하다. */
    fun cancel(reason: String) {
        if (status != PaymentStatus.COMPLETED) throw PaymentCancelRequiresCompletedPaymentException()
        status = PaymentStatus.CANCELLED
        domainEvents += PaymentCancelledEvent(paymentId, accountId, ownerId, amount, reason, LocalDateTime.now())
    }

    fun pullDomainEvents(): List<PaymentDomainEvent> = domainEvents.toList().also { domainEvents.clear() }
}

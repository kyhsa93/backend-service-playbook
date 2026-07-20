package com.example.accountservice.payment.application.command

import com.example.accountservice.payment.application.adapter.AccountAdapter
import com.example.accountservice.payment.application.adapter.CardAdapter
import com.example.accountservice.payment.domain.InsufficientBalanceException
import com.example.accountservice.payment.domain.LinkedAccountNotFoundException
import com.example.accountservice.payment.domain.LinkedCardNotFoundException
import com.example.accountservice.payment.domain.Payment
import com.example.accountservice.payment.domain.PaymentRepository
import com.example.accountservice.payment.domain.PaymentRequiresActiveAccountException
import com.example.accountservice.payment.domain.PaymentRequiresActiveCardException
import org.springframework.stereotype.Service

/**
 * 결제 처리(카드+잔액 확인) — Adapter(동기 읽기) 조합, Domain Service가 아니다.
 *
 * 1. 동기 Adapter → Card BC: 카드가 ACTIVE인지, 연결된 accountId 확인(Card가 이미 Account를 조회하는
 *    것과 같은 ACL 패턴을 재사용).
 * 2. 동기 Adapter → Account BC: 잔액 ≥ 결제 금액인지 확인(읽기 전용 판단, "결제 가능 여부").
 * 3. 통과하면 Payment를 생성하고 즉시 COMPLETED로 전환, PaymentCompletedEvent(Domain Event)를
 *    발행해 Outbox에 적재한다.
 *
 * 실제 잔액 차감은 이 동기 호출이 아니라 `payment.completed.v1` Integration Event를 Account BC가
 * 구독해 비동기로 수행한다(cross-domain.md의 "동기=조회, 비동기 Integration Event=상태변경" 원칙).
 */
@Service
class CreatePaymentService(
    private val paymentRepository: PaymentRepository,
    private val cardAdapter: CardAdapter,
    private val accountAdapter: AccountAdapter,
) {
    fun create(command: CreatePaymentCommand): CreatePaymentResult {
        val card = cardAdapter.findCard(command.cardId, command.requesterId) ?: throw LinkedCardNotFoundException()
        if (!card.active) throw PaymentRequiresActiveCardException()

        val account =
            accountAdapter.findAccount(card.accountId, command.requesterId) ?: throw LinkedAccountNotFoundException()
        if (!account.active) throw PaymentRequiresActiveAccountException()
        if (account.balanceAmount < command.amount) throw InsufficientBalanceException()

        val payment =
            Payment.create(cardId = command.cardId, accountId = card.accountId, ownerId = command.requesterId, amount = command.amount)
        payment.complete()

        paymentRepository.savePayment(payment)

        return CreatePaymentResult(
            paymentId = payment.paymentId,
            cardId = payment.cardId,
            accountId = payment.accountId,
            ownerId = payment.ownerId,
            amount = payment.amount,
            status = payment.status.name,
            createdAt = payment.createdAt,
        )
    }
}

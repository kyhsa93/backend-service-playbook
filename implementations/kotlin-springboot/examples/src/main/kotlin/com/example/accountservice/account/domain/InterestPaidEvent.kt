package com.example.accountservice.account.domain

import java.time.LocalDateTime

/**
 * [Account.payInterest]가 실제로 이자를 적립했을 때(0원이 아닐 때만) 발행하는 Domain Event다.
 * MoneyDepositedEvent와 형태는 같지만, 이자 지급은 시스템(Scheduler → Task Queue)이 발생시키는
 * 것이지 사용자 Command가 아니므로 별도 이벤트 타입으로 구분한다(scheduling.md — Task Queue는
 * "명령: X를 수행하라", Domain Event는 "사실: X가 일어났다"이며, 이 이벤트는 그 Task 실행의
 * 결과로 Aggregate가 발행하는 사실이다).
 */
data class InterestPaidEvent(
    override val accountId: String,
    override val email: String,
    val transactionId: String,
    val amount: Money,
    val balanceAfter: Money,
    val paidAt: LocalDateTime,
) : DomainEvent

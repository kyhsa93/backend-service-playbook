package com.example.accountservice.payment.infrastructure

import com.example.accountservice.account.application.query.AccountQuery
import com.example.accountservice.account.domain.AccountStatus
import com.example.accountservice.payment.application.adapter.AccountAdapter
import com.example.accountservice.payment.application.adapter.AccountView
import org.springframework.stereotype.Component

/**
 * [AccountAdapter]의 구현체(ACL). Account BC가 공개한 읽기 포트([AccountQuery])를 주입받아 호출하고,
 * Account BC의 모델을 Payment BC가 쓰는 최소 형태([AccountView])로 번역한다 — 잔액(balanceAmount)까지
 * 포함해 "결제 가능 여부" 판단에 필요한 형태로 옮긴다.
 *
 * 클래스명에 `Payment` 접두어를 붙인 이유: Card BC에도 동일한 역할의
 * [com.example.accountservice.card.infrastructure.AccountAdapterImpl]이 이미 존재하는데, Spring의
 * 기본 빈 이름 생성은 (패키지가 달라도) 단순 클래스명만 보므로 두 `AccountAdapterImpl`이
 * `ConflictingBeanDefinitionException`을 일으킨다 — 실제로 이 충돌을 겪고 나서 접두어로 고쳤다.
 */
@Component
class PaymentAccountAdapterImpl(
    private val accountQuery: AccountQuery,
) : AccountAdapter {
    override fun findAccount(
        accountId: String,
        ownerId: String,
    ): AccountView? =
        accountQuery.findByAccountIdAndOwnerId(accountId, ownerId)?.let { account ->
            AccountView(
                accountId = account.accountId,
                active = account.status == AccountStatus.ACTIVE,
                balanceAmount = account.balance.amount,
                currency = account.balance.currency,
            )
        }
}

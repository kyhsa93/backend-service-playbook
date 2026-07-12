package com.example.accountservice.card.infrastructure

import com.example.accountservice.account.application.query.AccountQuery
import com.example.accountservice.account.domain.AccountStatus
import com.example.accountservice.card.application.adapter.AccountAdapter
import com.example.accountservice.card.application.adapter.AccountView
import org.springframework.stereotype.Component

/**
 * [AccountAdapter]의 구현체(ACL). Account BC가 공개한 읽기 포트([AccountQuery])를 주입받아 호출하고,
 * Account BC의 모델([com.example.accountservice.account.domain.Account]·[AccountStatus])을 Card BC가
 * 쓰는 최소 형태([AccountView])로 번역한다. Account의 쓰기 Repository/도메인 메서드는 참조하지 않는다.
 *
 * Account의 "계좌 없음" 신호는 `AccountQuery.findByAccountIdAndOwnerId`가 `null`로 반환하며,
 * 이를 그대로 Card 도메인이 이해하는 `null`로 전파한다 — Account의 예외 타입(AccountNotFoundException 등)이
 * Card 레이어로 누수되지 않는다.
 */
@Component
class AccountAdapterImpl(
    private val accountQuery: AccountQuery,
) : AccountAdapter {

    override fun findAccount(accountId: String, ownerId: String): AccountView? =
        accountQuery.findByAccountIdAndOwnerId(accountId, ownerId)?.let { account ->
            AccountView(accountId = account.accountId, active = account.status == AccountStatus.ACTIVE)
        }
}

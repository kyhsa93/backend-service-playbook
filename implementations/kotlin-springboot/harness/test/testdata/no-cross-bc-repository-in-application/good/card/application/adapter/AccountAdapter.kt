package com.example.accountservice.card.application.adapter

// 다른 도메인(account)의 domain/*Repository·*Query를 직접 import하지 않고, 자기 BC 안에 정의한
// Adapter 인터페이스만 노출한다 — 구현체(infrastructure/AccountAdapterImpl)가 실제 조회를 담당한다.
interface AccountAdapter {
    fun findAccount(accountId: String): AccountView?
}

data class AccountView(val accountId: String, val active: Boolean)

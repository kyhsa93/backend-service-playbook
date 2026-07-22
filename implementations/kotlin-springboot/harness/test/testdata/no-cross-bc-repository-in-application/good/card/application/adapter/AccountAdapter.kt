package com.example.accountservice.card.application.adapter

// Does not directly import another domain's(account) domain/*Repository·*Query, and exposes only an
// Adapter interface defined inside its own BC — the implementation(infrastructure/AccountAdapterImpl)
// handles the actual lookup.
interface AccountAdapter {
    fun findAccount(accountId: String): AccountView?
}

data class AccountView(val accountId: String, val active: Boolean)

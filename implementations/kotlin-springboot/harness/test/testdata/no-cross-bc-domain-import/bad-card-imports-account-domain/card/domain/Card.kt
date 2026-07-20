package com.example.accountservice.card.domain

// 위반 — 다른 BC(account)의 domain/ 패키지를 직접 import. accountId: String 같은 ID 참조만
// 허용된다(tactical-ddd.md).
import com.example.accountservice.account.domain.Account

class Card private constructor() {
    var cardId: String = ""
        private set

    var account: Account? = null
        private set

    companion object {
        fun create(account: Account): Card =
            Card().apply {
                this.cardId = "card-1"
                this.account = account
            }
    }
}

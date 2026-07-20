package com.example.accountservice.card.domain

import com.example.accountservice.common.generateId

// accountId는 ID 참조만 — 다른 BC(account)의 domain/ 타입을 import하지 않는다.
class Card private constructor() {
    var cardId: String = ""
        private set

    var accountId: String = ""
        private set

    companion object {
        fun create(accountId: String): Card =
            Card().apply {
                this.cardId = generateId()
                this.accountId = accountId
            }
    }
}

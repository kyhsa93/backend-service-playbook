package com.example.accountservice.card.domain

import com.example.accountservice.common.generateId

// accountId is an ID reference only — does not import another BC's(account) domain/ type.
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

package com.example.accountservice.card.domain

// Violation — directly imports another BC's(account) domain/ package. Only ID references like
// accountId: String are allowed(tactical-ddd.md).
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

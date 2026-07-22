package com.example.accountservice.card.application.command

import com.example.accountservice.card.application.adapter.AccountAdapter
import com.example.accountservice.card.domain.Card
import com.example.accountservice.card.domain.CardIssueRequiresActiveAccountException
import com.example.accountservice.card.domain.CardRepository
import com.example.accountservice.card.domain.LinkedAccountNotFoundException
import org.springframework.stereotype.Service

@Service
class IssueCardService(
    private val cardRepository: CardRepository,
    private val accountAdapter: AccountAdapter,
) {
    fun issue(command: IssueCardCommand): IssueCardResult {
        // Query the linked account via the synchronous Adapter (ACL) — a synchronous call is needed
        // because the response (whether issuance is allowed) depends on it.
        val account =
            accountAdapter.findAccount(command.accountId, command.requesterId)
                ?: throw LinkedAccountNotFoundException()
        if (!account.active) throw CardIssueRequiresActiveAccountException()

        val card = Card.issue(accountId = command.accountId, ownerId = command.requesterId, brand = command.brand)
        cardRepository.saveCard(card)
        return IssueCardResult(
            cardId = card.cardId,
            accountId = card.accountId,
            ownerId = card.ownerId,
            brand = card.brand,
            status = card.status.name,
            createdAt = card.createdAt,
        )
    }
}

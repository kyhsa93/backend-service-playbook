package com.example.accountservice.card.domain

sealed class CardException(
    message: String,
    val code: CardErrorCode,
) : RuntimeException(message)

class CardNotFoundException(
    cardId: String,
) : CardException("card not found: $cardId", CardErrorCode.CARD_NOT_FOUND)

class LinkedAccountNotFoundException : CardException("Could not find the account to link.", CardErrorCode.LINKED_ACCOUNT_NOT_FOUND)

class CardIssueRequiresActiveAccountException :
    CardException("A card can only be issued for an active account.", CardErrorCode.CARD_ISSUE_REQUIRES_ACTIVE_ACCOUNT)

class CancelledCardCannotBeSuspendedException :
    CardException("A cancelled card cannot be suspended.", CardErrorCode.CANCELLED_CARD_CANNOT_BE_SUSPENDED)

class CardAlreadySuspendedException : CardException("This card is already suspended.", CardErrorCode.CARD_ALREADY_SUSPENDED)

class CardAlreadyCancelledException : CardException("This card is already cancelled.", CardErrorCode.CARD_ALREADY_CANCELLED)

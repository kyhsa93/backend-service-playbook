package com.example.accountservice.card.domain

sealed class CardException(
    message: String,
    val code: CardErrorCode,
) : RuntimeException(message)

class CardNotFoundException(
    cardId: String,
) : CardException("card not found: $cardId", CardErrorCode.CARD_NOT_FOUND)

class LinkedAccountNotFoundException : CardException("연결할 계좌를 찾을 수 없습니다.", CardErrorCode.LINKED_ACCOUNT_NOT_FOUND)

class CardIssueRequiresActiveAccountException :
    CardException("활성 상태의 계좌만 카드를 발급할 수 있습니다.", CardErrorCode.CARD_ISSUE_REQUIRES_ACTIVE_ACCOUNT)

class CancelledCardCannotBeSuspendedException : CardException("해지된 카드는 정지할 수 없습니다.", CardErrorCode.CANCELLED_CARD_CANNOT_BE_SUSPENDED)

class CardAlreadySuspendedException : CardException("이미 정지된 카드입니다.", CardErrorCode.CARD_ALREADY_SUSPENDED)

class CardAlreadyCancelledException : CardException("이미 해지된 카드입니다.", CardErrorCode.CARD_ALREADY_CANCELLED)

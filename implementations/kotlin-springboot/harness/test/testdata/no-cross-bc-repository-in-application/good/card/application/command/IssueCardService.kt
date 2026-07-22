package com.example.accountservice.card.application.command

import com.example.accountservice.card.domain.CardRepository
import org.springframework.stereotype.Service

// Using domain/CardRepository from the same domain(card) is a normal pattern — not targeted.
@Service
class IssueCardService(
    private val cardRepository: CardRepository,
)

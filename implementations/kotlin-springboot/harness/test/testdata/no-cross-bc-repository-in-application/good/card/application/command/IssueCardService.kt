package com.example.accountservice.card.application.command

import com.example.accountservice.card.domain.CardRepository
import org.springframework.stereotype.Service

// 같은 도메인(card)의 domain/CardRepository를 쓰는 것은 정상 패턴 — 대상이 아니다.
@Service
class IssueCardService(
    private val cardRepository: CardRepository,
)

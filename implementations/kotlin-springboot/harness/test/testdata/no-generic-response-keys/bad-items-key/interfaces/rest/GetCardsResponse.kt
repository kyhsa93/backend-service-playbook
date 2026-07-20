package com.example.accountservice.card.interfaces.rest

data class GetCardsResponse(
    val items: List<String>,
    val count: Long,
)

package com.example.accountservice.card.interfaces.rest

import com.example.accountservice.card.application.command.IssueCardCommand
import com.example.accountservice.card.application.command.IssueCardResult
import com.example.accountservice.card.application.command.IssueCardService
import com.example.accountservice.card.application.query.GetCardResult
import com.example.accountservice.card.application.query.GetCardService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/cards")
class CardController(
    private val issueCardService: IssueCardService,
    private val getCardService: GetCardService,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun issueCard(
        authentication: Authentication,
        @Valid @RequestBody request: IssueCardRequest,
    ): IssueCardResult = issueCardService.issue(IssueCardCommand(request.accountId, request.brand, authentication.name))

    @GetMapping("/{cardId}")
    fun getCard(
        authentication: Authentication,
        @PathVariable cardId: String,
    ): GetCardResult = getCardService.getCard(cardId, authentication.name)
}

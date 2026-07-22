package com.example.accountservice.card.interfaces.rest

import com.example.accountservice.account.interfaces.rest.ErrorResponse
import com.example.accountservice.card.application.command.IssueCardCommand
import com.example.accountservice.card.application.command.IssueCardResult
import com.example.accountservice.card.application.command.IssueCardService
import com.example.accountservice.card.application.query.GetCardResult
import com.example.accountservice.card.application.query.GetCardService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
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
@Tag(name = "Card")
@SecurityRequirement(name = "bearerAuth")
@ApiResponses(
    ApiResponse(
        responseCode = "401",
        description = "The bearer token is missing, malformed, or invalid.",
        content = [Content(schema = Schema(implementation = ErrorResponse::class))],
    ),
)
class CardController(
    private val issueCardService: IssueCardService,
    private val getCardService: GetCardService,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
        summary = "Issue a new card",
        description = "Issues a new card linked to the given account. The account must belong to the requester and be active.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "The card was issued."),
        ApiResponse(
            responseCode = "400",
            description =
                "One of: linked account not active (`CARD_ISSUE_REQUIRES_ACTIVE_ACCOUNT`), or validation failed " +
                    "(`VALIDATION_FAILED`) — e.g. missing `accountId`/`brand`.",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "No account exists with the given `accountId` for this requester (`LINKED_ACCOUNT_NOT_FOUND`).",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    fun issueCard(
        authentication: Authentication,
        @Valid @RequestBody request: IssueCardRequest,
    ): IssueCardResult = issueCardService.issue(IssueCardCommand(request.accountId, request.brand, authentication.name))

    @GetMapping("/{cardId}")
    @Operation(summary = "Look up a card", description = "Returns the card only if it belongs to the authenticated requester.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "The card was found."),
        ApiResponse(
            responseCode = "404",
            description = "No card exists with the given `cardId` for this requester (`CARD_NOT_FOUND`).",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    fun getCard(
        authentication: Authentication,
        @PathVariable cardId: String,
    ): GetCardResult = getCardService.getCard(cardId, authentication.name)
}

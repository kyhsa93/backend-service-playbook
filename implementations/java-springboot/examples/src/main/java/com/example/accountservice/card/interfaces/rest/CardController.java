package com.example.accountservice.card.interfaces.rest;

import com.example.accountservice.account.interfaces.rest.ErrorResponse;
import com.example.accountservice.card.application.command.IssueCardCommand;
import com.example.accountservice.card.application.command.IssueCardResult;
import com.example.accountservice.card.application.command.IssueCardService;
import com.example.accountservice.card.application.query.GetCardResult;
import com.example.accountservice.card.application.query.GetCardService;
import com.example.accountservice.card.domain.CardException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

import static net.logstash.logback.argument.StructuredArguments.kv;

@RestController
@RequestMapping("/cards")
@RequiredArgsConstructor
public class CardController {

    private static final Logger log = LoggerFactory.getLogger(CardController.class);

    private static final Set<CardException.ErrorCode> NOT_FOUND_CODES = Set.of(
            CardException.ErrorCode.CARD_NOT_FOUND,
            CardException.ErrorCode.LINKED_ACCOUNT_NOT_FOUND);

    private final IssueCardService issueCardService;
    private final GetCardService getCardService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public IssueCardResult issueCard(
            Authentication authentication,
            @Valid @RequestBody IssueCardRequest request
    ) {
        String requesterId = authentication.getName();
        return issueCardService.issue(new IssueCardCommand(request.accountId(), request.brand(), requesterId));
    }

    @GetMapping("/{cardId}")
    public GetCardResult getCard(Authentication authentication, @PathVariable String cardId) {
        return getCardService.getCard(cardId, authentication.getName());
    }

    @ExceptionHandler(CardException.class)
    public ResponseEntity<ErrorResponse> handleCardException(CardException e) {
        HttpStatus status = NOT_FOUND_CODES.contains(e.code()) ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
        log.warn("카드 요청 실패", kv("code", e.code()), kv("message", e.getMessage()));
        return ResponseEntity.status(status).body(ErrorResponse.of(status, e.code().name(), e.getMessage()));
    }
}

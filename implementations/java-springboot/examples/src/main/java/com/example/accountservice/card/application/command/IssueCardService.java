package com.example.accountservice.card.application.command;

import com.example.accountservice.card.application.adapter.AccountAdapter;
import com.example.accountservice.card.domain.Card;
import com.example.accountservice.card.domain.CardException;
import com.example.accountservice.card.domain.CardRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class IssueCardService {

    private final CardRepository cardRepository;
    private final AccountAdapter accountAdapter;

    public IssueCardResult issue(IssueCardCommand command) {
        // Query the linked account via the synchronous Adapter (ACL) — a synchronous call is
        // needed because the response (whether issuance succeeds) depends on it.
        AccountAdapter.AccountView account =
                accountAdapter
                        .findAccount(command.accountId(), command.requesterId())
                        .orElseThrow(
                                () ->
                                        new CardException(
                                                CardException.ErrorCode.LINKED_ACCOUNT_NOT_FOUND,
                                                "The account to link could not be found."));
        if (!account.active()) {
            throw new CardException(
                    CardException.ErrorCode.CARD_ISSUE_REQUIRES_ACTIVE_ACCOUNT,
                    "Only an active account can have a card issued.");
        }

        Card card = Card.issue(command.accountId(), command.requesterId(), command.brand());
        cardRepository.saveCard(card);
        return new IssueCardResult(
                card.getCardId(),
                card.getAccountId(),
                card.getOwnerId(),
                card.getBrand(),
                card.getStatus().name(),
                card.getCreatedAt());
    }
}

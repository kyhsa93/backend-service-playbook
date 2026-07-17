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
        // 동기 Adapter(ACL)로 연결 계좌를 조회 — 응답(발급 가부)에 필요하므로 동기 호출.
        AccountAdapter.AccountView account =
                accountAdapter
                        .findAccount(command.accountId(), command.requesterId())
                        .orElseThrow(
                                () ->
                                        new CardException(
                                                CardException.ErrorCode.LINKED_ACCOUNT_NOT_FOUND,
                                                "연결할 계좌를 찾을 수 없습니다."));
        if (!account.active()) {
            throw new CardException(
                    CardException.ErrorCode.CARD_ISSUE_REQUIRES_ACTIVE_ACCOUNT,
                    "활성 상태의 계좌만 카드를 발급할 수 있습니다.");
        }

        Card card = Card.issue(command.accountId(), command.requesterId(), command.brand());
        cardRepository.save(card);
        return new IssueCardResult(
                card.getCardId(),
                card.getAccountId(),
                card.getOwnerId(),
                card.getBrand(),
                card.getStatus().name(),
                card.getCreatedAt());
    }
}

package com.example.accountservice.card.application.command;

import com.example.accountservice.card.application.adapter.AccountAdapter;
import com.example.accountservice.card.application.adapter.PaymentAdapter;
import com.example.accountservice.card.application.service.NotificationService;
import com.example.accountservice.card.domain.Card;
import com.example.accountservice.card.domain.CardFindQuery;
import com.example.accountservice.card.domain.CardRepository;
import com.example.accountservice.card.domain.CardStatus;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 매월 1회 배치로 호출되는 시스템 유스케이스(월간 카드 사용내역 안내) — {@code interfaces/task/
 * SendCardStatementTaskController}가 Task Queue 메시지를 받아 호출한다(scheduling.md Feature 2). ACTIVE 카드를
 * 페이지 단위로 순회하며 이번 달 안내를 아직 보내지 않은 카드({@link Card#shouldSendStatement})에 한해 Payment BC 사용내역 통계를
 * 조회(PaymentAdapter, ACL)해 계좌 소유자에게 알림을 보낸다. {@code lastStatementSentMonth} 갱신이 재발송을 막는 멱등성 Ledger
 * 역할을 한다(Level 2, domain-events.md 멱등성 3단계).
 */
@Service
@RequiredArgsConstructor
public class SendCardStatementService {

    private static final int PAGE_SIZE = 100;

    private final CardRepository cardRepository;
    private final AccountAdapter accountAdapter;
    private final PaymentAdapter paymentAdapter;
    private final NotificationService notificationService;

    public void sendStatements(SendCardStatementCommand command) {
        YearMonth month = command.month();
        LocalDateTime from = month.atDay(1).atStartOfDay();
        LocalDateTime to = month.plusMonths(1).atDay(1).atStartOfDay();

        int page = 0;
        while (true) {
            List<Card> cards =
                    cardRepository
                            .findCards(
                                    new CardFindQuery(
                                            page,
                                            PAGE_SIZE,
                                            null,
                                            null,
                                            null,
                                            List.of(CardStatus.ACTIVE)))
                            .cards();
            if (cards.isEmpty()) {
                break;
            }
            for (Card card : cards) {
                if (card.shouldSendStatement(month)) {
                    sendStatement(card, month, from, to);
                }
            }
            if (cards.size() < PAGE_SIZE) {
                break;
            }
            page++;
        }
    }

    private void sendStatement(Card card, YearMonth month, LocalDateTime from, LocalDateTime to) {
        // 연결 계좌가 이미 삭제된 방어적 케이스 — 보낼 곳이 없으므로 이번 달은 건너뛴다(다음 달 배치가
        // 다시 판단한다. lastStatementSentMonth를 갱신하지 않으므로 재시도 대상으로 남는다는 점에서도
        // 안전하다).
        AccountAdapter.AccountView account =
                accountAdapter.findAccount(card.getAccountId(), card.getOwnerId()).orElse(null);
        if (account == null) {
            return;
        }

        PaymentAdapter.UsageSummary usage =
                paymentAdapter.summarizeUsage(card.getCardId(), from, to);

        notificationService.sendEmail(
                card.getCardId(),
                "CardStatement",
                account.email(),
                "[Card] " + month + " 카드 사용내역 안내",
                month + " 동안 " + usage.count() + "건, 총 " + usage.totalAmount() + "원을 사용하셨습니다.");

        card.markStatementSent(month);
        cardRepository.saveCard(card);
    }
}

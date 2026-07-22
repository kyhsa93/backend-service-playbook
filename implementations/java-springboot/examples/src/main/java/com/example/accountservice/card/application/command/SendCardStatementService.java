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
 * A system use case invoked once a month by a batch job (monthly card usage statement) — invoked by
 * {@code interfaces/task/SendCardStatementTaskController} when it receives a Task Queue message
 * (scheduling.md Feature 2). It pages through ACTIVE cards and, for each card that hasn't yet been
 * sent this month's statement ({@link Card#shouldSendStatement}), looks up usage statistics from
 * the Payment BC (PaymentAdapter, ACL) and sends a notification to the account owner. Updating
 * {@code lastStatementSentMonth} acts as an idempotency ledger that prevents resending (Level 2,
 * domain-events.md idempotency tier 3).
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
        // Defensive case where the linked account has already been deleted — there's nowhere to
        // send the notification, so this month is skipped (next month's batch will re-evaluate.
        // It's also safe in that lastStatementSentMonth is not updated, so the card remains
        // eligible for retry).
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
                "[Card] Your " + month + " card statement",
                "You made "
                        + usage.count()
                        + " transaction(s) totaling "
                        + usage.totalAmount()
                        + " KRW during "
                        + month
                        + ".");

        card.markStatementSent(month);
        cardRepository.saveCard(card);
    }
}

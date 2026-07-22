package com.example.accountservice.card.infrastructure;

import com.example.accountservice.card.application.adapter.PaymentAdapter;
import com.example.accountservice.payment.application.query.PaymentQuery;
import com.example.accountservice.payment.application.query.PaymentUsageSummary;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The implementation of {@link PaymentAdapter} (ACL). Injects and calls the read interface exposed
 * by the Payment BC ({@link PaymentQuery}), and translates the Payment BC's result into the minimal
 * shape the Card BC uses ({@link PaymentAdapter.UsageSummary}) — the ACL running in the opposite
 * direction, symmetric to {@code payment/infrastructure/PaymentCardAdapterImpl}.
 *
 * <p>The reason the class is named {@code CardPaymentAdapterImpl} is the same as for {@code
 * PaymentAccountAdapterImpl} — simply naming it {@code PaymentAdapterImpl} would leave room for a
 * class-name collision if another BC later adds another implementation that queries the Payment BC
 * (Spring bean names must be globally unique even across different packages).
 */
@Component
@RequiredArgsConstructor
public class CardPaymentAdapterImpl implements PaymentAdapter {

    private final PaymentQuery paymentQuery;

    @Override
    public UsageSummary summarizeUsage(String cardId, LocalDateTime from, LocalDateTime to) {
        PaymentUsageSummary summary = paymentQuery.summarizeCardUsage(cardId, from, to);
        return new UsageSummary(summary.count(), summary.totalAmount());
    }
}

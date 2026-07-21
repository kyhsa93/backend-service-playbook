package com.example.accountservice.card.infrastructure;

import com.example.accountservice.card.application.adapter.PaymentAdapter;
import com.example.accountservice.payment.application.query.PaymentQuery;
import com.example.accountservice.payment.application.query.PaymentUsageSummary;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@link PaymentAdapter}의 구현체(ACL). Payment BC가 노출한 읽기 인터페이스({@link PaymentQuery})를 주입받아 호출하고,
 * Payment BC의 결과를 Card BC가 쓰는 최소 형태({@link PaymentAdapter.UsageSummary})로 번역한다 — {@code
 * payment/infrastructure/PaymentCardAdapterImpl}과 대칭되는 반대 방향 ACL이다.
 *
 * <p>클래스명이 {@code CardPaymentAdapterImpl}인 이유는 {@code PaymentAccountAdapterImpl}과 같다 — 단순히 {@code
 * PaymentAdapterImpl}로 두면 향후 다른 BC가 Payment BC를 조회하는 또 다른 구현체를 추가할 때 클래스명이 충돌할 여지를 남긴다(Spring 빈 이름은
 * 패키지가 달라도 전역으로 유일해야 함).
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

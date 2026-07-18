package com.example.accountservice.payment.application.command;

import com.example.accountservice.outbox.OutboxRelay;
import com.example.accountservice.payment.application.query.GetRefundResult;
import com.example.accountservice.payment.domain.Payment;
import com.example.accountservice.payment.domain.PaymentException;
import com.example.accountservice.payment.domain.PaymentFindQuery;
import com.example.accountservice.payment.domain.PaymentRepository;
import com.example.accountservice.payment.domain.Refund;
import com.example.accountservice.payment.domain.RefundDecision;
import com.example.accountservice.payment.domain.RefundEligibilityService;
import com.example.accountservice.payment.domain.RefundRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RequestRefundService {

    // RefundEligibilityService는 프레임워크 애노테이션이 없는 순수 Domain Service다.
    // Spring 빈으로 등록하지 않고 직접 인스턴스화해 쓴다(상태 없는 순수 판단 로직이라 매 요청 재사용에 문제가 없다).
    private final RefundEligibilityService refundEligibilityService =
            new RefundEligibilityService();

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final OutboxRelay outboxRelay;

    public GetRefundResult request(RequestRefundCommand command) {
        Payment payment =
                paymentRepository
                        .findPayments(
                                new PaymentFindQuery(
                                        0, 1, command.paymentId(), command.requesterId()))
                        .payments()
                        .stream()
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new PaymentException(
                                                PaymentException.ErrorCode.PAYMENT_NOT_FOUND,
                                                "결제를 찾을 수 없습니다."));

        Refund refund = Refund.create(payment.getPaymentId(), command.amount(), command.reason());

        // 어느 한 Aggregate만으로는 내릴 수 없는 판단(원 결제 상태 + 환불 금액 비교)을 Payment+Refund
        // 두 Aggregate를 함께 로드한 이 Application 레이어가 RefundEligibilityService(Domain
        // Service)에 위임해 조율한다.
        RefundDecision decision = refundEligibilityService.evaluate(payment, refund);
        if (decision.approved()) {
            refund.approve(payment.getAccountId(), payment.getOwnerId());
        } else {
            // 환불 거부는 도메인 관점에서 유효한 상태 전이다(입력이 잘못된 것이 아니라 두 Aggregate를
            // 조율해 내린 결론) — 따라서 이 메서드는 throw하지 않고 REJECTED로 저장한 Refund를 그대로
            // 반환한다. Interface 레이어가 이를 에러가 아닌 201 + status:REJECTED로 응답한다.
            refund.reject(decision.reason() != null ? decision.reason() : "환불 요청이 거부되었습니다.");
        }

        refundRepository.saveRefund(refund);
        // RefundApprovedEvent → refund.approved.v1을 Account BC가 구독해 환불 크레딧을 실행한다.
        // 거부된 경우에는 Domain Event가 없으므로 드레인할 것이 없어 no-op이다.
        outboxRelay.processPending();
        return GetRefundResult.from(refund);
    }
}

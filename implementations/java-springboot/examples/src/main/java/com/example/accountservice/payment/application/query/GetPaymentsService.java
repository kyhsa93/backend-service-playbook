package com.example.accountservice.payment.application.query;

import com.example.accountservice.payment.domain.PaymentFindQuery;
import com.example.accountservice.payment.domain.PaymentsWithCount;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 내역 목록 조회. {@code ownerId}는 인증된 요청자(Authentication)에서만 얻는다 — 이 저장소는 클라이언트가 보낸 소유자 id를 신뢰하는
 * 엔드포인트를 두지 않는다(api-response.md의 "목록 조회는 인증된 사용자 범위로 한정" 원칙).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GetPaymentsService {

    private final PaymentQuery paymentQuery;

    public GetPaymentsResult getPayments(String requesterId, int page, int take) {
        PaymentsWithCount result =
                paymentQuery.findPayments(new PaymentFindQuery(page, take, null, requesterId));
        return new GetPaymentsResult(
                result.payments().stream().map(GetPaymentResult::from).toList(), result.count());
    }
}

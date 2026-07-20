package com.example.accountservice.payment.application.command;

import com.example.accountservice.payment.application.adapter.AccountAdapter;
import com.example.accountservice.payment.application.adapter.CardAdapter;
import com.example.accountservice.payment.application.query.GetPaymentResult;
import com.example.accountservice.payment.domain.Payment;
import com.example.accountservice.payment.domain.PaymentException;
import com.example.accountservice.payment.domain.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CreatePaymentService {

    private final PaymentRepository paymentRepository;
    private final CardAdapter cardAdapter;
    private final AccountAdapter accountAdapter;

    public GetPaymentResult create(CreatePaymentCommand command) {
        // 동기 Adapter(ACL)로 카드가 존재·활성 상태인지 확인 — 응답(결제 가부)에 필요하므로 동기 호출.
        CardAdapter.CardView card =
                cardAdapter
                        .findCard(command.cardId(), command.requesterId())
                        .orElseThrow(
                                () ->
                                        new PaymentException(
                                                PaymentException.ErrorCode.LINKED_CARD_NOT_FOUND,
                                                "연결할 카드를 찾을 수 없습니다."));
        if (!card.active()) {
            throw new PaymentException(
                    PaymentException.ErrorCode.PAYMENT_REQUIRES_ACTIVE_CARD,
                    "활성 상태의 카드로만 결제할 수 있습니다.");
        }

        // 동기 Adapter(ACL)로 연결 계좌가 활성이고 잔액이 충분한지 확인(읽기 전용 판단).
        // 실제 차감은 여기서 하지 않는다 — PaymentCompletedEvent → payment.completed.v1을
        // Account BC가 구독해 비동기로 수행한다(cross-domain.md의 "동기=조회, 비동기=상태변경" 원칙).
        AccountAdapter.AccountView account =
                accountAdapter
                        .findAccount(card.accountId(), command.requesterId())
                        .orElseThrow(
                                () ->
                                        new PaymentException(
                                                PaymentException.ErrorCode.LINKED_ACCOUNT_NOT_FOUND,
                                                "연결된 계좌를 찾을 수 없습니다."));
        if (!account.active()) {
            throw new PaymentException(
                    PaymentException.ErrorCode.PAYMENT_REQUIRES_ACTIVE_ACCOUNT,
                    "활성 상태의 계좌로만 결제할 수 있습니다.");
        }
        if (account.balanceAmount() < command.amount()) {
            throw new PaymentException(
                    PaymentException.ErrorCode.INSUFFICIENT_BALANCE, "계좌 잔액이 부족하여 결제할 수 없습니다.");
        }

        Payment payment =
                Payment.create(
                        command.cardId(),
                        card.accountId(),
                        command.requesterId(),
                        command.amount());
        payment.complete();

        paymentRepository.savePayment(payment);
        return GetPaymentResult.from(payment);
    }
}

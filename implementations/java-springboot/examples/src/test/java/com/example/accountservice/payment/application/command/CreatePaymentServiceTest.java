package com.example.accountservice.payment.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.accountservice.payment.application.adapter.AccountAdapter;
import com.example.accountservice.payment.application.adapter.CardAdapter;
import com.example.accountservice.payment.application.query.GetPaymentResult;
import com.example.accountservice.payment.domain.PaymentException;
import com.example.accountservice.payment.domain.PaymentRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CreatePaymentServiceTest {

    @Mock private PaymentRepository paymentRepository;

    @Mock private CardAdapter cardAdapter;

    @Mock private AccountAdapter accountAdapter;

    private CreatePaymentService service;

    @BeforeEach
    void setUp() {
        service = new CreatePaymentService(paymentRepository, cardAdapter, accountAdapter);
    }

    private void stubActiveCardAndAccount(long balanceAmount) {
        when(cardAdapter.findCard("card-1", "owner-1"))
                .thenReturn(Optional.of(new CardAdapter.CardView("card-1", "account-1", true)));
        when(accountAdapter.findAccount("account-1", "owner-1"))
                .thenReturn(
                        Optional.of(
                                new AccountAdapter.AccountView(
                                        "account-1", true, balanceAmount, "KRW")));
    }

    @Test
    void 활성_카드와_충분한_잔액이면_결제를_완료처리하고_저장한다() {
        stubActiveCardAndAccount(5000);

        GetPaymentResult result =
                service.create(new CreatePaymentCommand("card-1", 1000, "owner-1"));

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.accountId()).isEqualTo("account-1");
        assertThat(result.amount()).isEqualTo(1000);
        verify(paymentRepository).savePayment(any());
    }

    @Test
    void 연결_카드를_찾을_수_없으면_예외를_던지고_저장하지_않는다() {
        when(cardAdapter.findCard("card-1", "owner-1")).thenReturn(Optional.empty());

        assertThatThrownBy(
                        () -> service.create(new CreatePaymentCommand("card-1", 1000, "owner-1")))
                .isInstanceOf(PaymentException.class)
                .extracting(e -> ((PaymentException) e).code())
                .isEqualTo(PaymentException.ErrorCode.LINKED_CARD_NOT_FOUND);
        verifyNoInteractions(paymentRepository);
    }

    @Test
    void 비활성_카드면_예외를_던지고_저장하지_않는다() {
        when(cardAdapter.findCard("card-1", "owner-1"))
                .thenReturn(Optional.of(new CardAdapter.CardView("card-1", "account-1", false)));

        assertThatThrownBy(
                        () -> service.create(new CreatePaymentCommand("card-1", 1000, "owner-1")))
                .isInstanceOf(PaymentException.class)
                .extracting(e -> ((PaymentException) e).code())
                .isEqualTo(PaymentException.ErrorCode.PAYMENT_REQUIRES_ACTIVE_CARD);
        verifyNoInteractions(paymentRepository);
    }

    @Test
    void 연결_계좌를_찾을_수_없으면_예외를_던지고_저장하지_않는다() {
        when(cardAdapter.findCard("card-1", "owner-1"))
                .thenReturn(Optional.of(new CardAdapter.CardView("card-1", "account-1", true)));
        when(accountAdapter.findAccount("account-1", "owner-1")).thenReturn(Optional.empty());

        assertThatThrownBy(
                        () -> service.create(new CreatePaymentCommand("card-1", 1000, "owner-1")))
                .isInstanceOf(PaymentException.class)
                .extracting(e -> ((PaymentException) e).code())
                .isEqualTo(PaymentException.ErrorCode.LINKED_ACCOUNT_NOT_FOUND);
        verifyNoInteractions(paymentRepository);
    }

    @Test
    void 비활성_계좌면_예외를_던지고_저장하지_않는다() {
        when(cardAdapter.findCard("card-1", "owner-1"))
                .thenReturn(Optional.of(new CardAdapter.CardView("card-1", "account-1", true)));
        when(accountAdapter.findAccount("account-1", "owner-1"))
                .thenReturn(
                        Optional.of(
                                new AccountAdapter.AccountView("account-1", false, 5000, "KRW")));

        assertThatThrownBy(
                        () -> service.create(new CreatePaymentCommand("card-1", 1000, "owner-1")))
                .isInstanceOf(PaymentException.class)
                .extracting(e -> ((PaymentException) e).code())
                .isEqualTo(PaymentException.ErrorCode.PAYMENT_REQUIRES_ACTIVE_ACCOUNT);
        verifyNoInteractions(paymentRepository);
    }

    @Test
    void 잔액이_부족하면_예외를_던지고_저장하지_않는다() {
        stubActiveCardAndAccount(500);

        assertThatThrownBy(
                        () -> service.create(new CreatePaymentCommand("card-1", 1000, "owner-1")))
                .isInstanceOf(PaymentException.class)
                .extracting(e -> ((PaymentException) e).code())
                .isEqualTo(PaymentException.ErrorCode.INSUFFICIENT_BALANCE);
        verifyNoInteractions(paymentRepository);
    }
}

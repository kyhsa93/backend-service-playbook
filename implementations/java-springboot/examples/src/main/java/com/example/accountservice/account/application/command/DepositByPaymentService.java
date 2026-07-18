package com.example.accountservice.account.application.command;

import com.example.accountservice.account.domain.Account;
import com.example.accountservice.account.domain.AccountFindQuery;
import com.example.accountservice.account.domain.AccountRepository;
import com.example.accountservice.account.domain.TransactionType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Payment BC의 {@code payment.cancelled.v1}(결제취소 보상 크레딧) 및 {@code refund.approved.v1}(환불 승인 크레딧)
 * Integration Event 둘 다에 대한 반응 유스케이스다 — 두 이벤트는 "이미 차감된 금액을 되돌린다"는 동일한 동작이고 referenceId(paymentId 또는
 * refundId)만 다르므로 Command를 하나로 재사용한다.
 *
 * <p>멱등성은 {@link WithdrawByPaymentService}와 동일한 이유로 Level 2 Ledger를 쓴다. 공유 Outbox 드레인 컴포넌트를 생성자로
 * 주입받지 않는 이유도 {@link WithdrawByPaymentService}와 동일하다(순환 빈 의존성 방지 — 이 서비스는 항상 {@code
 * OutboxEventHandler} 구현체를 거쳐 진행 중인 드레인 루프 안에서만 호출된다).
 */
@Service
@RequiredArgsConstructor
public class DepositByPaymentService {

    private final AccountRepository accountRepository;

    public void deposit(DepositByPaymentCommand command) {
        boolean alreadyProcessed =
                accountRepository.hasTransactionWithReference(
                        command.referenceId(), TransactionType.DEPOSIT);
        if (alreadyProcessed) {
            return;
        }

        Account account =
                accountRepository
                        .findAccounts(new AccountFindQuery(0, 1, command.accountId(), null, null))
                        .accounts()
                        .stream()
                        .findFirst()
                        .orElse(null);
        if (account == null) {
            return;
        }

        account.deposit(command.amount(), command.referenceId());
        accountRepository.saveAccount(account);
    }
}

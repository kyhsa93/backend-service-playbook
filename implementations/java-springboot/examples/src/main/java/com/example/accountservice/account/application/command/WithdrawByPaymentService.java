package com.example.accountservice.account.application.command;

import com.example.accountservice.account.domain.Account;
import com.example.accountservice.account.domain.AccountFindQuery;
import com.example.accountservice.account.domain.AccountRepository;
import com.example.accountservice.account.domain.TransactionType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Payment BC의 {@code payment.completed.v1} Integration Event에 대한 반응 유스케이스 — 결제 시점에 이미 동기 Adapter로
 * 판정된 차감을 여기서 실제로 수행한다.
 *
 * <p>멱등성: {@link WithdrawService}(사용자 직접 출금)와 달리 이 반응은 같은 referenceId(paymentId)의 WITHDRAWAL 거래가 이미
 * 있으면 조용히 무시한다 — Card의 상태 기반 멱등성과 달리 금액 이동은 반복 적용하면 잔액이 계속 줄어들므로 "이미 처리했는지"를 확인해야 한다(Level 2
 * Ledger, domain-events.md 참고).
 *
 * <p>공유 Outbox 드레인 컴포넌트(outbox 패키지의 릴레이)를 생성자로 주입받지 않는다 — 이 서비스는 항상 {@code
 * PaymentCompletedIntegrationEventHandler}(자신도 {@code OutboxEventHandler}로서 그 릴레이의 진행 중인 드레인 루프 안에서
 * 호출됨)를 거쳐서만 실행된다. 여기서 그 릴레이를 주입하면 릴레이가 자신이 생성 중인 핸들러 목록(List&lt;OutboxEventHandler&gt;) 안의 이
 * 서비스로부터 다시 자기 자신을 요구하게 되는 순환 빈 의존성이 생겨 컨텍스트 기동이 실패한다(Card의 {@code SuspendCardsByAccountService}가
 * 같은 이유로 그 릴레이를 쓰지 않는 것과 동일한 제약). 이 메서드가 새로 적재하는 {@code MoneyWithdrawnEvent}는 이미 실행 중인 바깥 드레인 호출의
 * 다음 패스에서 자동으로 처리된다.
 */
@Service
@RequiredArgsConstructor
public class WithdrawByPaymentService {

    private final AccountRepository accountRepository;

    public void withdraw(WithdrawByPaymentCommand command) {
        boolean alreadyProcessed =
                accountRepository.hasTransactionWithReference(
                        command.referenceId(), TransactionType.WITHDRAWAL);
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
            return; // 반응할 대상 계좌가 없으면 조용히 무시한다(예: 계좌가 이미 삭제됨).
        }

        account.withdraw(command.amount(), command.referenceId());
        accountRepository.saveAccount(account);
    }
}

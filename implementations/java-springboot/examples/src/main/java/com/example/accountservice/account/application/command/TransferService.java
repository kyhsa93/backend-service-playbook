package com.example.accountservice.account.application.command;

import com.example.accountservice.account.domain.Account;
import com.example.accountservice.account.domain.AccountException;
import com.example.accountservice.account.domain.AccountFindQuery;
import com.example.accountservice.account.domain.AccountRepository;
import com.example.accountservice.account.domain.Transaction;
import com.example.accountservice.account.domain.TransferDecision;
import com.example.accountservice.account.domain.TransferEligibilityService;
import com.example.accountservice.common.IdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 계좌 간 송금(Transfer) — Command Service 자신은 트랜잭션 애노테이션을 갖지 않는다. 트랜잭션 경계는 {@link
 * AccountRepository#saveAccounts}(Repository 레벨)에 있다(persistence.md,
 * WithdrawService/DepositService와 동일한 규칙 — Command Service에 트랜잭션 애노테이션을 다시 붙이는 것은 회귀다).
 */
@Service
@RequiredArgsConstructor
public class TransferService {

    private final AccountRepository accountRepository;
    // TransferEligibilityService는 프레임워크 애노테이션이 없는 순수 Domain Service다. Spring
    // 빈으로 등록하지 않고 직접 인스턴스화해 쓴다(RefundEligibilityService와 동일한 이유).
    private final TransferEligibilityService transferEligibilityService =
            new TransferEligibilityService();

    public TransferResult transfer(TransferCommand command) {
        Account source =
                accountRepository
                        .findAccounts(
                                new AccountFindQuery(
                                        0,
                                        1,
                                        command.sourceAccountId(),
                                        command.requesterId(),
                                        null))
                        .accounts()
                        .stream()
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new AccountException(
                                                AccountException.ErrorCode.ACCOUNT_NOT_FOUND,
                                                "계좌를 찾을 수 없습니다."));
        // target은 소유자 필터 없이 조회한다 — 타인 계좌로 송금하는 것이 이 기능의 목적이라,
        // 존재+활성 여부만 확인하면 된다(소유권 확인은 source에만 적용).
        Account target =
                accountRepository
                        .findAccounts(
                                new AccountFindQuery(0, 1, command.targetAccountId(), null, null))
                        .accounts()
                        .stream()
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new AccountException(
                                                AccountException.ErrorCode.ACCOUNT_NOT_FOUND,
                                                "계좌를 찾을 수 없습니다."));

        TransferDecision decision =
                transferEligibilityService.evaluate(source, target, command.amount());
        if (!decision.approved()) {
            throw new AccountException(decision.code(), decision.reason());
        }

        // transferId는 이 송금 전용의 새 영속 Aggregate를 두지 않고, 두 Transaction 행을
        // 상관관계 짓는 referenceId로만 쓴다 — (reference_id, type) 조합이 이미 유니크하므로
        // source(WITHDRAWAL)/target(DEPOSIT) 두 행이 같은 transferId를 공유해도 충돌하지
        // 않는다. 접미사 없이 32자리 원본 그대로 쓴다(정기이체 벤치마크에서 접미사를 붙여
        // VARCHAR(36)을 넘긴 전례가 있다 — 이 기능은 그 전례를 되풀이하지 않는다).
        String transferId = IdGenerator.generate();
        Transaction sourceTransaction = source.withdraw(command.amount(), transferId);
        Transaction targetTransaction = target.deposit(command.amount(), transferId);

        // 두 Account 저장을 하나의 물리 트랜잭션으로 묶는다 — 그렇지 않으면 "출금은
        // 반영됐는데 입금은 유실됨" 실패 모드가 생긴다.
        accountRepository.saveAccounts(source, target);

        return new TransferResult(
                transferId,
                new TransactionResult(
                        sourceTransaction.getTransactionId(),
                        sourceTransaction.getAccountId(),
                        sourceTransaction.getType().name(),
                        new TransactionResult.MoneyResult(
                                sourceTransaction.getAmount().amount(),
                                sourceTransaction.getAmount().currency()),
                        sourceTransaction.getCreatedAt()),
                new TransactionResult(
                        targetTransaction.getTransactionId(),
                        targetTransaction.getAccountId(),
                        targetTransaction.getType().name(),
                        new TransactionResult.MoneyResult(
                                targetTransaction.getAmount().amount(),
                                targetTransaction.getAmount().currency()),
                        targetTransaction.getCreatedAt()));
    }
}

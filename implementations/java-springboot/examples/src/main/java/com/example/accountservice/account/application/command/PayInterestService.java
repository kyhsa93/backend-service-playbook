package com.example.accountservice.account.application.command;

import com.example.accountservice.account.domain.Account;
import com.example.accountservice.account.domain.AccountFindQuery;
import com.example.accountservice.account.domain.AccountRepository;
import com.example.accountservice.account.domain.AccountStatus;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 매일 1회 배치로 호출되는 시스템 유스케이스(정기 이자 지급) — 사용자가 직접 호출하는 Command가 아니라 {@code
 * interfaces/task/PayInterestTaskController}가 Task Queue 메시지를 받아 호출한다(scheduling.md Feature 1). 활성
 * 계좌를 페이지 단위로 순회하며 실제 이자 계산·지급 판단은 {@link Account#payInterest}에 전부 위임한다 — 이 Service는 조회와 저장
 * 오케스트레이션만 한다.
 *
 * <p>트랜잭션 경계는 이 Command Service가 아니라 {@code AccountRepository.saveAccount()}에 있다
 * (transaction-boundary 규칙, persistence.md) — 계좌마다 개별 트랜잭션으로 저장되므로, 배치 도중 예외가 나도 이미 처리된 계좌는 그대로 커밋된
 * 채 남는다(다음 tick이 나머지를 처리, 멱등하므로 안전).
 */
@Service
@RequiredArgsConstructor
public class PayInterestService {

    private static final int PAGE_SIZE = 100;

    private final AccountRepository accountRepository;

    public void payInterest(PayInterestCommand command) {
        int page = 0;
        while (true) {
            List<Account> accounts =
                    accountRepository
                            .findAccounts(
                                    new AccountFindQuery(
                                            page,
                                            PAGE_SIZE,
                                            null,
                                            null,
                                            List.of(AccountStatus.ACTIVE.name())))
                            .accounts();
            if (accounts.isEmpty()) {
                break;
            }
            for (Account account : accounts) {
                account.payInterest(command.date())
                        .ifPresent(transaction -> accountRepository.saveAccount(account));
            }
            if (accounts.size() < PAGE_SIZE) {
                break;
            }
            page++;
        }
    }
}

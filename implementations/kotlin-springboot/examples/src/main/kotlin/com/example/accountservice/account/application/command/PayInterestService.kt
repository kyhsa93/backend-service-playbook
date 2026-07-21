package com.example.accountservice.account.application.command

import com.example.accountservice.account.domain.AccountFindQuery
import com.example.accountservice.account.domain.AccountRepository
import com.example.accountservice.account.domain.AccountStatus
import org.springframework.stereotype.Service
import java.time.LocalDate

/**
 * 정기 이자 지급 유스케이스 — [com.example.accountservice.account.interfaces.task.PayInterestTaskController]
 * (Task Queue의 `account.pay-interest` 핸들러)가 호출한다. 사용자가 직접 요청하는 Command가
 * 아니라 시스템(Scheduler)이 발생시키는 배치 작업이므로, DepositService처럼 REST에서 바로 호출되는
 * `*Command` DTO가 없다 — [payDate] 하나만 받는다(root scheduling.md의 "Task Controller는 로직
 * 없이 Command 위임"에 맞춰, 날짜 계산 자체는 여기(Application)가 아니라
 * `InterestPaymentScheduler`가 enqueue 시점에 이미 끝내고 payload로 넘겨준다).
 *
 * Repository 조회 단계에서 `status = ACTIVE`이면서 `excludeInterestPaidDate = payDate`(이미 오늘
 * 이자를 받은 계좌 제외)로 이미 걸러내므로, 루프 안에서는 [com.example.accountservice.account.domain.Account.payInterest]가
 * 반환하는 `null`(방어적 재확인)만 걸러내면 된다.
 */
@Service
class PayInterestService(
    private val accountRepository: AccountRepository,
) {
    fun payInterest(payDate: LocalDate) {
        val (accounts, _) =
            accountRepository.findAccounts(
                AccountFindQuery(
                    page = 0,
                    take = MAX_ACCOUNTS_PER_RUN,
                    status = listOf(AccountStatus.ACTIVE.name),
                    excludeInterestPaidDate = payDate,
                ),
            )
        for (account in accounts) {
            account.payInterest(payDate) ?: continue
            accountRepository.saveAccount(account)
        }
    }

    companion object {
        // 한 Task 실행당 처리할 계좌 수 상한 — CancelCardsByAccountService(take = 1000)와 동일한
        // 취지로, REST 페이지네이션 대상이 아닌 내부 배치 루프이므로 충분히 크게 준다. 계좌 수가 이
        // 값을 넘어서는 규모(이 저장소가 다루는 데모 범위를 넘는 실사용 시나리오)에서는 하루치 Task
        // 하나로 전체를 못 끝낼 수 있다 — 진짜 대규모 배치라면 이 upper-bound take 방식 대신 커서
        // 기반 페이지네이션으로 여러 Task/여러 tick에 나눠 처리하도록 재설계해야 한다(알려진 한계,
        // 이 저장소 범위 밖으로 남겨둔다).
        private const val MAX_ACCOUNTS_PER_RUN = 10_000
    }
}

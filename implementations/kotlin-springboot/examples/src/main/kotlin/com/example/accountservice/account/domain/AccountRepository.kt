package com.example.accountservice.account.domain

import java.time.LocalDate

interface AccountRepository {
    fun findAccounts(query: AccountFindQuery): Pair<List<Account>, Long>

    fun saveAccount(account: Account)

    /**
     * source/target 두 Account를 하나의 물리 트랜잭션으로 저장한다 — Transfer(계좌 간 송금)처럼
     * 서로 다른 두 Account 인스턴스에 대한 저장이 원자적으로 함께 커밋되거나 함께 롤백돼야 하는
     * 유스케이스 전용이다. 단일 Account만 저장하면 되는 기존 유스케이스는 계속 [saveAccount]를
     * 쓴다(persistence.md — 트랜잭션 경계는 Command Service가 아니라 이 Repository의 save*
     * 메서드에 있다).
     */
    fun saveAccounts(
        source: Account,
        target: Account,
    )

    fun deleteAccount(accountId: String)

    fun findTransactions(query: TransactionFindQuery): Pair<List<Transaction>, Long>

    /**
     * Payment BC의 Integration Event 반응(WithdrawByPaymentService/DepositByPaymentService)이
     * at-least-once 재수신에도 같은 거래를 두 번 만들지 않도록 확인하는 멱등성 체크다(Level 2
     * Ledger — domain-events.md 참고). Card의 상태 기반 멱등성(이미 정지된 카드는 다시 정지해도
     * 무해)과 달리 금액 이동은 반복 적용하면 결과가 달라지므로 별도의 처리 여부 확인이 필요하다.
     *
     * [type]도 함께 확인해야 한다 — 결제완료(WITHDRAWAL)와 그 결제취소 보상 크레딧(DEPOSIT)은 같은
     * paymentId를 referenceId로 공유하는 서로 다른 거래이므로, referenceId만으로 확인하면 보상
     * 크레딧이 "이미 처리됨"으로 잘못 판정되어 스킵된다.
     */
    fun hasTransactionWithReference(
        referenceId: String,
        type: TransactionType,
    ): Boolean
}

data class AccountFindQuery(
    val page: Int,
    val take: Int,
    val accountId: String? = null,
    val ownerId: String? = null,
    val status: List<String>? = null,
    // PayInterestService 전용 필터 — 이미 이 날짜에 이자를 받은 계좌(lastInterestPaidAt = 이 값)를
    // 조회 단계에서 걸러낸다. Card의 CardFindQuery.excludeStatementMonth와 동일한 취지다: 이미
    // 처리된 행을 애플리케이션 레이어의 루프에서 스킵하는 대신 쿼리 자체가 걸러낸다.
    val excludeInterestPaidDate: LocalDate? = null,
)

data class TransactionFindQuery(
    val accountId: String,
    val page: Int,
    val take: Int,
)

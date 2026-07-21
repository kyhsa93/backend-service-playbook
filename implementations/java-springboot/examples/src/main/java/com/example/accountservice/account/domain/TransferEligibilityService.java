package com.example.accountservice.account.domain;

/**
 * Domain Service — 프레임워크 애노테이션이 없는 순수 클래스(Spring 빈으로 등록하지 않는다. Application 레이어가 필요할 때 {@code new}로
 * 직접 만들어 쓴다. root docs/architecture/domain-service.md 참고, {@link
 * com.example.accountservice.payment.domain.RefundEligibilityService}와 동일한 이유).
 *
 * <p>"출금 계좌와 입금 계좌가 서로 다르고, 둘 다 활성 상태이며, 통화가 같고, 출금 계좌 잔액이 충분한가"라는 판단은 어느 한쪽 {@link Account}
 * 인스턴스만으로는 내릴 수 없다 — 두 Aggregate 인스턴스를 모두 로드해 같은 자리에서 비교해야 한다.
 */
public class TransferEligibilityService {

    public TransferDecision evaluate(Account source, Account target, long amount) {
        if (source.getAccountId().equals(target.getAccountId())) {
            return TransferDecision.rejected(
                    AccountException.ErrorCode.TRANSFER_SAME_ACCOUNT, "출금 계좌와 입금 계좌가 동일할 수 없습니다.");
        }
        if (source.getStatus() != AccountStatus.ACTIVE) {
            return TransferDecision.rejected(
                    AccountException.ErrorCode.WITHDRAW_REQUIRES_ACTIVE_ACCOUNT,
                    "활성 상태의 계좌만 출금할 수 있습니다.");
        }
        if (target.getStatus() != AccountStatus.ACTIVE) {
            return TransferDecision.rejected(
                    AccountException.ErrorCode.DEPOSIT_REQUIRES_ACTIVE_ACCOUNT,
                    "활성 상태의 계좌만 입금할 수 있습니다.");
        }
        if (!source.getBalance().currency().equals(target.getBalance().currency())) {
            return TransferDecision.rejected(
                    AccountException.ErrorCode.CURRENCY_MISMATCH, "통화가 일치하지 않습니다.");
        }
        if (source.getBalance().amount() < amount) {
            return TransferDecision.rejected(
                    AccountException.ErrorCode.INSUFFICIENT_BALANCE, "잔액이 부족합니다.");
        }
        return TransferDecision.approve();
    }
}

package account

// TransferDecision은 EvaluateTransferEligibility의 판단 결과다. 거부 시 Err은 사용자가
// 직접 Withdraw/Deposit을 호출했을 때와 완전히 동일한 sentinel error다 — Transfer는
// Refund와 달리 자신만의 영속 Aggregate가 없어(거부를 저장할 대상이 없음) 거부가 곧바로
// 호출부의 에러 반환으로 이어져야 하고, 그 에러는 직접 호출과 클라이언트 입장에서
// 구분할 수 없어야 한다.
type TransferDecision struct {
	Approved bool
	Err      error
}

// EvaluateTransferEligibility는 root docs/architecture/domain-service.md가 정의하는
// "여러 Aggregate를 조율하는 순수 도메인 로직"이다 — EvaluateRefundEligibility와 동일한
// 이유로 상태 없는 패키지 함수로 표현한다.
//
// "출금 계좌와 입금 계좌가 서로 다르고, 둘 다 활성 상태이며, 통화가 같고, 출금 계좌
// 잔액이 충분한가"라는 판단은 어느 한쪽 Account만으로는 내릴 수 없다 — 두 Aggregate
// 인스턴스를 모두 로드해 같은 자리에서 비교해야 한다.
func EvaluateTransferEligibility(source, target *Account, amount int64) TransferDecision {
	if source.AccountID == target.AccountID {
		return TransferDecision{Approved: false, Err: ErrTransferSameAccount}
	}
	if source.Status != StatusActive {
		return TransferDecision{Approved: false, Err: ErrWithdrawRequiresActiveAccount}
	}
	if target.Status != StatusActive {
		return TransferDecision{Approved: false, Err: ErrDepositRequiresActiveAccount}
	}
	if source.Balance.Currency != target.Balance.Currency {
		return TransferDecision{Approved: false, Err: ErrCurrencyMismatch}
	}
	if source.Balance.Amount < amount {
		return TransferDecision{Approved: false, Err: ErrInsufficientBalance}
	}
	return TransferDecision{Approved: true}
}

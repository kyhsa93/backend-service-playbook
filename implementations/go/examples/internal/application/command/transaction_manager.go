package command

import "context"

// TransactionManager는 하나의 Command Handler가 둘 이상의 Repository 쓰기를 원자적으로
// 묶어야 할 때 의존하는 최소 포트다(root docs/architecture/layer-architecture.md의
// TransactionManager를 Go로 옮긴 것 — StatementNotifier/AccountAdapter와 동일하게, 사용
// 하는 곳 근처에 인터페이스를 선언하고 구현체는 infrastructure/database.Manager가
// 구조적으로 만족하게 한다).
//
// TransferHandler가 첫 사용처다 — 출금 계좌 저장과 입금 계좌 저장 두 번의 SaveAccount
// 호출이 하나의 커밋으로 묶이지 않으면 "출금은 반영됐는데 입금은 유실됨" 실패 모드가
// 생긴다.
type TransactionManager interface {
	RunInTx(ctx context.Context, fn func(ctx context.Context) error) error
}

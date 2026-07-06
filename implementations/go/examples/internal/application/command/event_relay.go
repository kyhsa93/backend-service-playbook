package command

import "context"

// OutboxRelay는 저장 트랜잭션이 커밋된 직후 outbox에 쌓인 미처리 도메인 이벤트를
// 모두 드레인하는 포트다. 실제 구현(outbox.Relay)은 event_type별 핸들러를 실행하고,
// 개별 이벤트 처리 실패는 내부에서 로깅 후 다음 호출 때 재시도되도록 남겨둔다 —
// 여기서 반환되는 에러는 그런 개별 실패가 아니라 outbox 테이블 자체를 읽지 못하는 등의
// 시스템 레벨 실패다.
type OutboxRelay interface {
	ProcessPending(ctx context.Context) error
}

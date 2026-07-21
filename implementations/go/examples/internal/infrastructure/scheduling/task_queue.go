package scheduling

import "context"

// TaskQueue는 Scheduler가 Task를 적재하기 위해 필요로 하는 최소 포트다 — 사용하는
// 곳(Scheduler) 근처에 선언하는 Go 관용구(authentication.md의 TokenIssuer/
// PasswordHasher와 동일)로, infrastructure/task-queue.Writer가 이 시그니처를
// 구조적으로 만족한다(별도 선언 없이 컴파일 타임에 검증됨).
type TaskQueue interface {
	Enqueue(ctx context.Context, taskType string, payload []byte, dedupID string) error
}

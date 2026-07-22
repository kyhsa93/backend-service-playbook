package scheduling

import "context"

// TaskQueue is the minimal port a Scheduler needs to load a Task — a Go
// idiom of declaring it near where it's used (Scheduler) (the same as
// TokenIssuer/PasswordHasher in authentication.md); infrastructure/
// task-queue.Writer structurally satisfies this signature (verified at
// compile time with no separate declaration).
type TaskQueue interface {
	Enqueue(ctx context.Context, taskType string, payload []byte, dedupID string) error
}

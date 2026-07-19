package main

import "testing"

func TestCheckSharedInfra(t *testing.T) {
	t.Run("good", func(t *testing.T) {
		result := checkSharedInfra("testdata/shared-infra/good")
		if got := countKind(result, Fail); got != 0 {
			t.Fatalf("want 0 failures, got %d: %+v", got, result.Findings)
		}
		if countKind(result, Pass) != 1 {
			t.Fatalf("want 1 pass (outbox Writer/Poller/Consumer confirmed), got %+v", result.Findings)
		}
		if countKind(result, Skip) != 1 {
			t.Fatalf("want 1 skip (task-queue not used), got %+v", result.Findings)
		}
	})

	t.Run("bad-outbox-missing-types", func(t *testing.T) {
		result := checkSharedInfra("testdata/shared-infra/bad-outbox-missing-types")
		if countKind(result, Fail) == 0 {
			t.Fatalf("want at least 1 failure (Writer/Poller/Consumer missing), got %+v", result.Findings)
		}
	})

	t.Run("bad-task-queue-misplaced", func(t *testing.T) {
		result := checkSharedInfra("testdata/shared-infra/bad-task-queue-misplaced")
		if countKind(result, Fail) == 0 {
			t.Fatalf("want at least 1 failure (task_queue file outside task-queue/), got %+v", result.Findings)
		}
	})
}

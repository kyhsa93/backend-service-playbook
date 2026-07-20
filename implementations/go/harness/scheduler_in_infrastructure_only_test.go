package main

import "testing"

func TestCheckSchedulerInInfrastructureOnly(t *testing.T) {
	t.Run("good", func(t *testing.T) {
		result := checkSchedulerInInfrastructureOnly("testdata/scheduler-in-infrastructure-only/good")
		if got := countKind(result, Fail); got != 0 {
			t.Fatalf("want 0 failures, got %d: %+v", got, result.Findings)
		}
	})

	t.Run("bad-application", func(t *testing.T) {
		result := checkSchedulerInInfrastructureOnly("testdata/scheduler-in-infrastructure-only/bad-application")
		if countKind(result, Fail) == 0 {
			t.Fatalf("want at least 1 failure, got %+v", result.Findings)
		}
	})
}

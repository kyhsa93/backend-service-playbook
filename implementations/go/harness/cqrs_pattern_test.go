package main

import "testing"

func TestCheckCQRSPattern(t *testing.T) {
	t.Run("good", func(t *testing.T) {
		result := checkCQRSPattern("testdata/cqrs-pattern/good")
		if got := countKind(result, Fail); got != 0 {
			t.Fatalf("want 0 failures, got %d: %+v", got, result.Findings)
		}
	})

	t.Run("bad-repository-in-query", func(t *testing.T) {
		result := checkCQRSPattern("testdata/cqrs-pattern/bad-repository-in-query")
		if countKind(result, Fail) == 0 {
			t.Fatalf("want at least 1 failure, got %+v", result.Findings)
		}
	})
}

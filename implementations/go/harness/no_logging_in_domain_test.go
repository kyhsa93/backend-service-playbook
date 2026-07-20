package main

import "testing"

func TestCheckNoLoggingInDomain(t *testing.T) {
	t.Run("good", func(t *testing.T) {
		result := checkNoLoggingInDomain("testdata/no-logging-in-domain/good")
		if got := countKind(result, Fail); got != 0 {
			t.Fatalf("want 0 failures, got %d: %+v", got, result.Findings)
		}
	})

	t.Run("bad-slog", func(t *testing.T) {
		result := checkNoLoggingInDomain("testdata/no-logging-in-domain/bad-slog")
		if countKind(result, Fail) == 0 {
			t.Fatalf("want at least 1 failure, got %+v", result.Findings)
		}
	})

	t.Run("bad-log", func(t *testing.T) {
		result := checkNoLoggingInDomain("testdata/no-logging-in-domain/bad-log")
		if countKind(result, Fail) == 0 {
			t.Fatalf("want at least 1 failure, got %+v", result.Findings)
		}
	})
}

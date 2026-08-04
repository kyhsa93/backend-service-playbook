package main

import (
	"strings"
	"testing"
)

func TestCheckUTCTimestampSource(t *testing.T) {
	t.Run("good", func(t *testing.T) {
		result := checkUTCTimestampSource("testdata/utc-timestamp-source/good")
		if got := countKind(result, Fail); got != 0 {
			t.Fatalf("want 0 failures, got %d: %+v", got, result.Findings)
		}
		// Only the domain file (common.Now()) and the persistence file
		// (time.Now().UTC()) are in scope — the middleware file measuring
		// elapsed time with a bare time.Now() is not reported at all.
		if got := countKind(result, Pass); got != 2 {
			t.Fatalf("want 2 passes, got %d: %+v", got, result.Findings)
		}
		for _, f := range result.Findings {
			if strings.Contains(f.Name, "middleware") {
				t.Fatalf("elapsed-time measurement outside the rule's scope was reported: %+v", f)
			}
		}
	})

	t.Run("bad-domain", func(t *testing.T) {
		result := checkUTCTimestampSource("testdata/utc-timestamp-source/bad-domain")
		if got := countKind(result, Fail); got != 1 {
			t.Fatalf("want 1 failure, got %d: %+v", got, result.Findings)
		}
		if !strings.Contains(result.Findings[0].Reason, "2 call(s)") {
			t.Fatalf("want both bare readings counted, got: %s", result.Findings[0].Reason)
		}
	})

	t.Run("bad-persistence", func(t *testing.T) {
		result := checkUTCTimestampSource("testdata/utc-timestamp-source/bad-persistence")
		if got := countKind(result, Fail); got != 1 {
			t.Fatalf("want 1 failure, got %d: %+v", got, result.Findings)
		}
	})

	t.Run("no-timestamp-layers-skip", func(t *testing.T) {
		// Neither internal/domain/ nor internal/infrastructure/persistence/
		// exists, so the rule has nothing to judge and skips entirely.
		result := checkUTCTimestampSource("testdata/utc-timestamp-source/no-timestamp-layers")
		if got := countKind(result, Skip); got != 1 {
			t.Fatalf("want 1 skip, got %d: %+v", got, result.Findings)
		}
	})
}

package main

import "testing"

func TestCheckRateLimitWired(t *testing.T) {
	t.Run("good", func(t *testing.T) {
		result := checkRateLimitWired("testdata/rate-limit-wired/good")
		if got := countKind(result, Fail); got != 0 {
			t.Fatalf("want 0 failures, got %d: %+v", got, result.Findings)
		}
		if got := countKind(result, Pass); got != 1 {
			t.Fatalf("want 1 pass, got %d: %+v", got, result.Findings)
		}
	})

	t.Run("bad-not-wired", func(t *testing.T) {
		result := checkRateLimitWired("testdata/rate-limit-wired/bad-not-wired")
		if got := countKind(result, Fail); got != 1 {
			t.Fatalf("want 1 failure, got %d: %+v", got, result.Findings)
		}
	})

	t.Run("no-middleware-skip", func(t *testing.T) {
		result := checkRateLimitWired("testdata/rate-limit-wired/no-middleware")
		if got := countKind(result, Skip); got != 1 {
			t.Fatalf("want 1 skip, got %d: %+v", got, result.Findings)
		}
	})
}

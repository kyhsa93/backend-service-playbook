package main

import "testing"

func TestCheckNoSilentCatch(t *testing.T) {
	t.Run("good", func(t *testing.T) {
		result := checkNoSilentCatch("testdata/no-silent-catch/good")
		if got := countKind(result, Fail); got != 0 {
			t.Fatalf("want 0 failures, got %d: %+v", got, result.Findings)
		}
		if countKind(result, Pass) == 0 {
			t.Fatalf("want at least 1 pass, got %+v", result.Findings)
		}
	})

	t.Run("bad", func(t *testing.T) {
		result := checkNoSilentCatch("testdata/no-silent-catch/bad")
		if countKind(result, Fail) == 0 {
			t.Fatalf("want at least 1 failure, got %+v", result.Findings)
		}
	})
}

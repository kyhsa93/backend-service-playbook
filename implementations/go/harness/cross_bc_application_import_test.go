package main

import "testing"

func TestCheckCrossBCRepositoryInApplication(t *testing.T) {
	t.Run("good", func(t *testing.T) {
		result := checkCrossBCRepositoryInApplication("testdata/no-cross-bc-repository-in-application/good")
		if got := countKind(result, Fail); got != 0 {
			t.Fatalf("want 0 failures, got %d: %+v", got, result.Findings)
		}
	})

	t.Run("bad", func(t *testing.T) {
		result := checkCrossBCRepositoryInApplication("testdata/no-cross-bc-repository-in-application/bad")
		if countKind(result, Fail) == 0 {
			t.Fatalf("want at least 1 failure, got %+v", result.Findings)
		}
	})
}

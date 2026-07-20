package main

import "testing"

func TestCheckNoGenericResponseKeys(t *testing.T) {
	t.Run("good", func(t *testing.T) {
		result := checkNoGenericResponseKeys("testdata/no-generic-response-keys/good")
		if got := countKind(result, Fail); got != 0 {
			t.Fatalf("want 0 failures, got %d: %+v", got, result.Findings)
		}
	})

	t.Run("bad-data-key", func(t *testing.T) {
		result := checkNoGenericResponseKeys("testdata/no-generic-response-keys/bad-data-key")
		if countKind(result, Fail) == 0 {
			t.Fatalf("want at least 1 failure, got %+v", result.Findings)
		}
	})
}

package main

import "testing"

func TestCheckHandlerPlacement(t *testing.T) {
	t.Run("good", func(t *testing.T) {
		result := checkHandlerPlacement("testdata/handler-placement/good")
		if got := countKind(result, Fail); got != 0 {
			t.Fatalf("want 0 failures, got %d: %+v", got, result.Findings)
		}
		if countKind(result, Pass) != 2 {
			t.Fatalf("want 2 passes (command handler + HTTP handler), got %+v", result.Findings)
		}
	})

	t.Run("bad-wrong-dir", func(t *testing.T) {
		result := checkHandlerPlacement("testdata/handler-placement/bad-wrong-dir")
		if countKind(result, Fail) == 0 {
			t.Fatalf("want at least 1 failure, got %+v", result.Findings)
		}
	})
}

package main

import "testing"

func TestCheckOutboxDrainOrder(t *testing.T) {
	t.Run("good", func(t *testing.T) {
		result := checkOutboxDrainOrder("testdata/outbox-drain-order/good")
		if got := countKind(result, Fail); got != 0 {
			t.Fatalf("want 0 failures, got %d: %+v", got, result.Findings)
		}
		if countKind(result, Pass) != 1 {
			t.Fatalf("want 1 pass, got %+v", result.Findings)
		}
	})

	t.Run("bad-missing-process-pending", func(t *testing.T) {
		result := checkOutboxDrainOrder("testdata/outbox-drain-order/bad-missing-process-pending")
		if countKind(result, Fail) == 0 {
			t.Fatalf("want at least 1 failure, got %+v", result.Findings)
		}
	})

	t.Run("bad-wrong-order", func(t *testing.T) {
		result := checkOutboxDrainOrder("testdata/outbox-drain-order/bad-wrong-order")
		if countKind(result, Fail) == 0 {
			t.Fatalf("want at least 1 failure, got %+v", result.Findings)
		}
	})
}

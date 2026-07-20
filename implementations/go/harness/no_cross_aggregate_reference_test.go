package main

import "testing"

func TestCheckNoCrossAggregateReference(t *testing.T) {
	t.Run("good", func(t *testing.T) {
		result := checkNoCrossAggregateReference("testdata/no-cross-aggregate-reference/good")
		if got := countKind(result, Fail); got != 0 {
			t.Fatalf("want 0 failures, got %d: %+v", got, result.Findings)
		}
		if countKind(result, Pass) != 2 {
			t.Fatalf("want 2 passes (payment.go + refund.go), got %+v", result.Findings)
		}
	})

	t.Run("bad-payment-refs-refund", func(t *testing.T) {
		result := checkNoCrossAggregateReference("testdata/no-cross-aggregate-reference/bad-payment-refs-refund")
		if countKind(result, Fail) == 0 {
			t.Fatalf("want at least 1 failure, got %+v", result.Findings)
		}
	})

	t.Run("bad-refund-refs-payment", func(t *testing.T) {
		result := checkNoCrossAggregateReference("testdata/no-cross-aggregate-reference/bad-refund-refs-payment")
		if countKind(result, Fail) == 0 {
			t.Fatalf("want at least 1 failure, got %+v", result.Findings)
		}
	})

	t.Run("no-payment-bc", func(t *testing.T) {
		result := checkNoCrossAggregateReference("testdata/no-cross-aggregate-reference/no-payment-bc")
		if countKind(result, Skip) == 0 {
			t.Fatalf("want a skip finding, got %+v", result.Findings)
		}
	})
}

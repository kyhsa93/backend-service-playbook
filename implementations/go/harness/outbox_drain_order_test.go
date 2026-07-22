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

	t.Run("bad-forbidden-symbol", func(t *testing.T) {
		// Even just holding OutboxRelay as a field (regardless of whether it is
		// called) is a violation — the synchronous-drain-forbidden rule blocks
		// the reference itself.
		result := checkOutboxDrainOrder("testdata/outbox-drain-order/bad-forbidden-symbol")
		if countKind(result, Fail) == 0 {
			t.Fatalf("want at least 1 failure, got %+v", result.Findings)
		}
	})

	t.Run("bad-forbidden-call", func(t *testing.T) {
		// Even when the preceding call has a different name, e.g. SaveRefund(...),
		// a subsequent ProcessPending(...) call must still be caught.
		result := checkOutboxDrainOrder("testdata/outbox-drain-order/bad-forbidden-call")
		if countKind(result, Fail) == 0 {
			t.Fatalf("want at least 1 failure, got %+v", result.Findings)
		}
	})

	t.Run("bad-poller-symbol", func(t *testing.T) {
		// References to the newer outbox.Poller/outbox.Consumer must be caught
		// too, not just OutboxRelay.
		result := checkOutboxDrainOrder("testdata/outbox-drain-order/bad-poller-symbol")
		if countKind(result, Fail) == 0 {
			t.Fatalf("want at least 1 failure, got %+v", result.Findings)
		}
	})

	t.Run("good-comment-only-mention", func(t *testing.T) {
		// A design-intent comment that merely mentions the identifier — e.g.
		// "does not reference OutboxRelay/outbox.Poller/outbox.Consumer" — must
		// still Pass as long as there is no actual field/type dependency; a
		// text-search-based check must not mistake a comment for code and FAIL.
		result := checkOutboxDrainOrder("testdata/outbox-drain-order/good-comment-only-mention")
		if got := countKind(result, Fail); got != 0 {
			t.Fatalf("want 0 failures, got %d: %+v", got, result.Findings)
		}
		if got := countKind(result, Pass); got != 1 {
			t.Fatalf("want 1 pass (only Save present, no actual drain reference), got %d: %+v", got, result.Findings)
		}
	})
}

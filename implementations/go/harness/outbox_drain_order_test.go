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
		// OutboxRelay를 필드로만 갖고 있어도(호출 여부와 무관하게) 위반이다 — 동기
		// 드레인 금지 규칙은 참조 자체를 막는다.
		result := checkOutboxDrainOrder("testdata/outbox-drain-order/bad-forbidden-symbol")
		if countKind(result, Fail) == 0 {
			t.Fatalf("want at least 1 failure, got %+v", result.Findings)
		}
	})

	t.Run("bad-forbidden-call", func(t *testing.T) {
		// SaveRefund(...)처럼 이름이 갈리는 변형이어도 뒤이은 ProcessPending(...) 호출은
		// 여전히 잡혀야 한다.
		result := checkOutboxDrainOrder("testdata/outbox-drain-order/bad-forbidden-call")
		if countKind(result, Fail) == 0 {
			t.Fatalf("want at least 1 failure, got %+v", result.Findings)
		}
	})

	t.Run("bad-poller-symbol", func(t *testing.T) {
		// OutboxRelay뿐 아니라 신규 outbox.Poller/outbox.Consumer 참조도 잡아야 한다.
		result := checkOutboxDrainOrder("testdata/outbox-drain-order/bad-poller-symbol")
		if countKind(result, Fail) == 0 {
			t.Fatalf("want at least 1 failure, got %+v", result.Findings)
		}
	})

	t.Run("good-comment-only-mention", func(t *testing.T) {
		// 설계 의도를 설명하는 주석이 "OutboxRelay/outbox.Poller/outbox.Consumer를
		// 참조하지 않는다"처럼 그 식별자를 언급만 해도, 실제 필드/타입 의존이 없으면
		// Pass여야 한다 — 텍스트 검색 기반 검사가 주석을 코드로 오인해 FAIL을 내면 안 된다.
		result := checkOutboxDrainOrder("testdata/outbox-drain-order/good-comment-only-mention")
		if got := countKind(result, Fail); got != 0 {
			t.Fatalf("want 0 failures, got %d: %+v", got, result.Findings)
		}
		if got := countKind(result, Pass); got != 1 {
			t.Fatalf("want 1 pass (Save만 있고 실제 드레인 참조 없음), got %d: %+v", got, result.Findings)
		}
	})
}

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

	// Payment BC 포팅(#Payment) 중 실제로 발견한 두 오탐 회귀 테스트.
	t.Run("good-save-variant-name", func(t *testing.T) {
		// SaveRefund(...)처럼 같은 패키지에 저장 대상이 여럿이라 이름이 갈리는 경우도
		// Save(...)와 동등하게 인정해야 한다 — 그렇지 않으면 Payment+Refund처럼 한
		// 패키지에 Aggregate가 둘인 도메인에서 정상 코드가 항상 FAIL로 오탐된다.
		result := checkOutboxDrainOrder("testdata/outbox-drain-order/good-save-variant-name")
		if got := countKind(result, Fail); got != 0 {
			t.Fatalf("want 0 failures, got %d: %+v", got, result.Findings)
		}
		if countKind(result, Pass) != 1 {
			t.Fatalf("want 1 pass, got %+v", result.Findings)
		}
	})

	t.Run("good-comment-only-mention", func(t *testing.T) {
		// 설계 의도를 설명하는 주석이 "OutboxRelay를 주입받지 않는다"처럼 그 식별자를
		// 언급만 해도, 실제 필드/타입 의존이 없으면 이 규칙의 대상이 아니어야 한다.
		// 텍스트 검색 기반 검사가 주석을 코드로 오인해 FAIL을 내면 안 된다.
		result := checkOutboxDrainOrder("testdata/outbox-drain-order/good-comment-only-mention")
		if got := countKind(result, Fail); got != 0 {
			t.Fatalf("want 0 failures, got %d: %+v", got, result.Findings)
		}
		if got := countKind(result, Pass); got != 0 {
			t.Fatalf("want 0 passes (not a real OutboxRelay dependency), got %d: %+v", got, result.Findings)
		}
	})
}

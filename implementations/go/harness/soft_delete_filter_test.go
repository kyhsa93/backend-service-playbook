package main

import "testing"

func TestCheckSoftDeleteFilter(t *testing.T) {
	t.Run("good", func(t *testing.T) {
		result := checkSoftDeleteFilter("testdata/soft-delete-filter/good")
		if got := countKind(result, Fail); got != 0 {
			t.Fatalf("want 0 failures, got %d: %+v", got, result.Findings)
		}
		if got := countKind(result, Pass); got != 1 {
			t.Fatalf("want 1 pass (only FindAccounts targets a soft-delete table), got %d: %+v", got, result.Findings)
		}
	})

	t.Run("bad-missing-filter", func(t *testing.T) {
		result := checkSoftDeleteFilter("testdata/soft-delete-filter/bad-missing-filter")
		if got := countKind(result, Fail); got != 1 {
			t.Fatalf("want 1 failure, got %d: %+v", got, result.Findings)
		}
	})

	t.Run("no-deleted-at-column-skip", func(t *testing.T) {
		// cards 테이블은 애초에 deleted_at 컬럼이 없으므로 규칙 전체가 skip으로 빠진다
		// (softDeleteTables가 비어 있음 — tablesWithDeletedAt 기준).
		result := checkSoftDeleteFilter("testdata/soft-delete-filter/no-deleted-at-column")
		if got := countKind(result, Fail); got != 0 {
			t.Fatalf("want 0 failures, got %d: %+v", got, result.Findings)
		}
		if got := countKind(result, Skip); got != 1 {
			t.Fatalf("want 1 skip, got %d: %+v", got, result.Findings)
		}
	})

	t.Run("no-migrations-skip", func(t *testing.T) {
		result := checkSoftDeleteFilter("testdata/soft-delete-filter/no-migrations")
		if got := countKind(result, Skip); got != 1 {
			t.Fatalf("want 1 skip, got %d: %+v", got, result.Findings)
		}
	})
}

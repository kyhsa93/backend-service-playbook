package main

import "testing"

func TestCheckRepositoryNaming(t *testing.T) {
	t.Run("good", func(t *testing.T) {
		result := checkRepositoryNaming("testdata/repository-naming/good")
		if got := countKind(result, Fail); got != 0 {
			t.Fatalf("want 0 failures, got %d: %+v", got, result.Findings)
		}
		if countKind(result, Pass) != 2 {
			t.Fatalf("want 2 passes (Query + Repository), got %+v", result.Findings)
		}
	})

	t.Run("bad-find-by", func(t *testing.T) {
		result := checkRepositoryNaming("testdata/repository-naming/bad-find-by")
		if countKind(result, Fail) == 0 {
			t.Fatalf("want at least 1 failure, got %+v", result.Findings)
		}
	})

	t.Run("bad-find-all", func(t *testing.T) {
		result := checkRepositoryNaming("testdata/repository-naming/bad-find-all")
		if countKind(result, Fail) == 0 {
			t.Fatalf("want at least 1 failure, got %+v", result.Findings)
		}
	})

	t.Run("bad-count", func(t *testing.T) {
		result := checkRepositoryNaming("testdata/repository-naming/bad-count")
		if countKind(result, Fail) == 0 {
			t.Fatalf("want at least 1 failure, got %+v", result.Findings)
		}
	})

	t.Run("bad-save", func(t *testing.T) {
		result := checkRepositoryNaming("testdata/repository-naming/bad-save")
		if countKind(result, Fail) == 0 {
			t.Fatalf("want at least 1 failure, got %+v", result.Findings)
		}
	})

	t.Run("bad-delete", func(t *testing.T) {
		result := checkRepositoryNaming("testdata/repository-naming/bad-delete")
		if countKind(result, Fail) == 0 {
			t.Fatalf("want at least 1 failure, got %+v", result.Findings)
		}
	})

	t.Run("good-infra-ignored", func(t *testing.T) {
		// infrastructure/persistence/의 구현체는 FindByID/Save 같은 이름이어도 이
		// 규칙의 대상이 아니다 — Repository/Query interface 선언만 검사한다.
		result := checkRepositoryNaming("testdata/repository-naming/good-infra-ignored")
		if got := countKind(result, Fail); got != 0 {
			t.Fatalf("want 0 failures, got %d: %+v", got, result.Findings)
		}
	})
}

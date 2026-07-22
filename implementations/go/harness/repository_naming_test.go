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

	t.Run("bad-update", func(t *testing.T) {
		result := checkRepositoryNaming("testdata/repository-naming/bad-update")
		if countKind(result, Fail) == 0 {
			t.Fatalf("want at least 1 failure, got %+v", result.Findings)
		}
	})

	t.Run("good-infra-ignored", func(t *testing.T) {
		// Implementations under infrastructure/persistence/ are out of scope for
		// this rule even if they use names like FindByID/Save — only Repository/Query
		// interface declarations are checked.
		result := checkRepositoryNaming("testdata/repository-naming/good-infra-ignored")
		if got := countKind(result, Fail); got != 0 {
			t.Fatalf("want 0 failures, got %d: %+v", got, result.Findings)
		}
	})
}

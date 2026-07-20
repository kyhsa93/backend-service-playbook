package main

import "testing"

func TestCheckTypedErrorsOnly(t *testing.T) {
	t.Run("good", func(t *testing.T) {
		result := checkTypedErrorsOnly("testdata/typed-errors-only/good")
		if got := countKind(result, Fail); got != 0 {
			t.Fatalf("want 0 failures, got %d: %+v", got, result.Findings)
		}
	})

	t.Run("bad-domain-errors-new", func(t *testing.T) {
		result := checkTypedErrorsOnly("testdata/typed-errors-only/bad-domain-errors-new")
		if got := countKind(result, Fail); got != 1 {
			t.Fatalf("want 1 failure, got %d: %+v", got, result.Findings)
		}
	})

	t.Run("bad-application-errors-new", func(t *testing.T) {
		result := checkTypedErrorsOnly("testdata/typed-errors-only/bad-application-errors-new")
		if got := countKind(result, Fail); got != 1 {
			t.Fatalf("want 1 failure, got %d: %+v", got, result.Findings)
		}
	})

	t.Run("bad-fmt-errorf-no-wrap", func(t *testing.T) {
		result := checkTypedErrorsOnly("testdata/typed-errors-only/bad-fmt-errorf-no-wrap")
		if got := countKind(result, Fail); got != 1 {
			t.Fatalf("want 1 failure, got %d: %+v", got, result.Findings)
		}
	})
}

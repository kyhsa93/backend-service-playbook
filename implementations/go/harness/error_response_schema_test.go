package main

import "testing"

func TestCheckErrorResponseSchema(t *testing.T) {
	t.Run("good", func(t *testing.T) {
		result := checkErrorResponseSchema("testdata/error-response-schema/good")
		if got := countKind(result, Fail); got != 0 {
			t.Fatalf("want 0 failures, got %d: %+v", got, result.Findings)
		}
		if got := countKind(result, Pass); got != 1 {
			t.Fatalf("want 1 pass, got %d: %+v", got, result.Findings)
		}
	})

	t.Run("bad-extra-field", func(t *testing.T) {
		result := checkErrorResponseSchema("testdata/error-response-schema/bad-extra-field")
		if got := countKind(result, Fail); got != 1 {
			t.Fatalf("want 1 failure, got %d: %+v", got, result.Findings)
		}
	})

	t.Run("bad-missing-field", func(t *testing.T) {
		result := checkErrorResponseSchema("testdata/error-response-schema/bad-missing-field")
		if got := countKind(result, Fail); got != 1 {
			t.Fatalf("want 1 failure, got %d: %+v", got, result.Findings)
		}
	})

	t.Run("no-candidate-skip", func(t *testing.T) {
		result := checkErrorResponseSchema("testdata/error-response-schema/no-candidate")
		if got := countKind(result, Skip); got != 1 {
			t.Fatalf("want 1 skip, got %d: %+v", got, result.Findings)
		}
	})
}

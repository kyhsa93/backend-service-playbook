package main

import "testing"

func TestCheckAggregateIDFormat(t *testing.T) {
	t.Run("good", func(t *testing.T) {
		result := checkAggregateIDFormat("testdata/aggregate-id-format/good")
		if got := countKind(result, Fail); got != 0 {
			t.Fatalf("want 0 failures, got %d: %+v", got, result.Findings)
		}
		if got := countKind(result, Pass); got != 1 {
			t.Fatalf("want 1 pass, got %d: %+v", got, result.Findings)
		}
	})

	t.Run("good-hex-encode", func(t *testing.T) {
		result := checkAggregateIDFormat("testdata/aggregate-id-format/good-hex-encode")
		if got := countKind(result, Fail); got != 0 {
			t.Fatalf("want 0 failures, got %d: %+v", got, result.Findings)
		}
	})

	t.Run("bad-raw-uuid", func(t *testing.T) {
		result := checkAggregateIDFormat("testdata/aggregate-id-format/bad-raw-uuid")
		if got := countKind(result, Fail); got != 1 {
			t.Fatalf("want 1 failure, got %d: %+v", got, result.Findings)
		}
	})

	t.Run("no-newid-skip", func(t *testing.T) {
		result := checkAggregateIDFormat("testdata/aggregate-id-format/no-newid")
		if got := countKind(result, Skip); got != 1 {
			t.Fatalf("want 1 skip, got %d: %+v", got, result.Findings)
		}
	})
}

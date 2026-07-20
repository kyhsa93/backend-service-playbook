package main

import "testing"

func TestCheckQueryHandlerNoRawAggregate(t *testing.T) {
	t.Run("good", func(t *testing.T) {
		result := checkQueryHandlerNoRawAggregate("testdata/query-handler-no-raw-aggregate/good")
		if got := countKind(result, Fail); got != 0 {
			t.Fatalf("want 0 failures, got %d: %+v", got, result.Findings)
		}
	})

	t.Run("bad-raw-aggregate", func(t *testing.T) {
		result := checkQueryHandlerNoRawAggregate("testdata/query-handler-no-raw-aggregate/bad-raw-aggregate")
		if countKind(result, Fail) == 0 {
			t.Fatalf("want at least 1 failure, got %+v", result.Findings)
		}
	})
}

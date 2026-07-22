package main

import "testing"

func TestCheckAPIDocumentation(t *testing.T) {
	t.Run("good", func(t *testing.T) {
		result := checkAPIDocumentation("testdata/api-documentation/good")
		if got := countKind(result, Fail); got != 0 {
			t.Fatalf("want 0 failures, got %d: %+v", got, result.Findings)
		}
		if got := countKind(result, Pass); got == 0 {
			t.Fatalf("want at least 1 pass, got %+v", result.Findings)
		}
	})

	t.Run("good-health-exempt", func(t *testing.T) {
		result := checkAPIDocumentation("testdata/api-documentation/good-health-exempt")
		if got := countKind(result, Fail); got != 0 {
			t.Fatalf("want 0 failures (a /health/* route is exempt from the @Failure requirement), got %d: %+v", got, result.Findings)
		}
		if got := countKind(result, Pass); got == 0 {
			t.Fatalf("want at least 1 pass, got %+v", result.Findings)
		}
	})

	t.Run("bad-missing-summary", func(t *testing.T) {
		result := checkAPIDocumentation("testdata/api-documentation/bad-missing-summary")
		if countKind(result, Fail) == 0 {
			t.Fatalf("want at least 1 failure, got %+v", result.Findings)
		}
	})

	t.Run("bad-no-failure", func(t *testing.T) {
		result := checkAPIDocumentation("testdata/api-documentation/bad-no-failure")
		if countKind(result, Fail) == 0 {
			t.Fatalf("want at least 1 failure, got %+v", result.Findings)
		}
	})

	t.Run("no-handlers", func(t *testing.T) {
		result := checkAPIDocumentation("testdata/api-documentation/no-handlers")
		if countKind(result, Skip) == 0 {
			t.Fatalf("want a skip finding, got %+v", result.Findings)
		}
	})
}

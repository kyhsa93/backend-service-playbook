package main

import "testing"

func TestCheckNoCrossBCDomainImport(t *testing.T) {
	t.Run("good", func(t *testing.T) {
		result := checkNoCrossBCDomainImport("testdata/no-cross-bc-domain-import/good")
		if got := countKind(result, Fail); got != 0 {
			t.Fatalf("want 0 failures, got %d: %+v", got, result.Findings)
		}
	})

	t.Run("bad-card-imports-payment", func(t *testing.T) {
		result := checkNoCrossBCDomainImport("testdata/no-cross-bc-domain-import/bad-card-imports-payment")
		if countKind(result, Fail) == 0 {
			t.Fatalf("want at least 1 failure, got %+v", result.Findings)
		}
	})
}

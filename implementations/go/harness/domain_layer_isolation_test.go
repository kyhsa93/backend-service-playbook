package main

import "testing"

func TestCheckDomainLayerIsolation(t *testing.T) {
	t.Run("good", func(t *testing.T) {
		result := checkDomainLayerIsolation("testdata/domain-layer-isolation/good")
		if got := countKind(result, Fail); got != 0 {
			t.Fatalf("want 0 failures, got %d: %+v", got, result.Findings)
		}
	})

	t.Run("bad-application", func(t *testing.T) {
		result := checkDomainLayerIsolation("testdata/domain-layer-isolation/bad-application")
		if countKind(result, Fail) == 0 {
			t.Fatalf("want at least 1 failure, got %+v", result.Findings)
		}
	})

	t.Run("bad-infrastructure", func(t *testing.T) {
		result := checkDomainLayerIsolation("testdata/domain-layer-isolation/bad-infrastructure")
		if countKind(result, Fail) == 0 {
			t.Fatalf("want at least 1 failure, got %+v", result.Findings)
		}
	})

	t.Run("bad-interface", func(t *testing.T) {
		result := checkDomainLayerIsolation("testdata/domain-layer-isolation/bad-interface")
		if countKind(result, Fail) == 0 {
			t.Fatalf("want at least 1 failure, got %+v", result.Findings)
		}
	})
}

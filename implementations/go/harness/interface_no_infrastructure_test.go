package main

import "testing"

func TestCheckInterfaceNoInfrastructure(t *testing.T) {
	t.Run("good", func(t *testing.T) {
		result := checkInterfaceNoInfrastructure("testdata/interface-no-infrastructure/good")
		if got := countKind(result, Fail); got != 0 {
			t.Fatalf("want 0 failures, got %d: %+v", got, result.Findings)
		}
	})

	t.Run("bad", func(t *testing.T) {
		result := checkInterfaceNoInfrastructure("testdata/interface-no-infrastructure/bad")
		if countKind(result, Fail) == 0 {
			t.Fatalf("want at least 1 failure, got %+v", result.Findings)
		}
	})
}

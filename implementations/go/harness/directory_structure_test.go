package main

import "testing"

func TestCheckDirectoryStructure(t *testing.T) {
	t.Run("good", func(t *testing.T) {
		result := checkDirectoryStructure("testdata/directory-structure/good")
		if got := countKind(result, Fail); got != 0 {
			t.Fatalf("want 0 failures, got %d: %+v", got, result.Findings)
		}
	})

	t.Run("bad-missing-layer", func(t *testing.T) {
		result := checkDirectoryStructure("testdata/directory-structure/bad-missing-layer")
		if countKind(result, Fail) == 0 {
			t.Fatalf("want at least 1 failure, got %+v", result.Findings)
		}
	})

	t.Run("no-internal-dir-skips", func(t *testing.T) {
		result := checkDirectoryStructure("testdata/directory-structure/does-not-exist")
		if countKind(result, Skip) != 1 {
			t.Fatalf("want exactly 1 skip, got %+v", result.Findings)
		}
	})
}

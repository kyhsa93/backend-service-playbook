package main

import "testing"

func TestCheckDockerfileConventions(t *testing.T) {
	t.Run("good", func(t *testing.T) {
		result := checkDockerfileConventions("testdata/dockerfile-conventions/good")
		if got := countKind(result, Fail); got != 0 {
			t.Fatalf("want 0 failures, got %d: %+v", got, result.Findings)
		}
	})

	t.Run("bad-single-stage", func(t *testing.T) {
		result := checkDockerfileConventions("testdata/dockerfile-conventions/bad-single-stage")
		if countKind(result, Fail) == 0 {
			t.Fatalf("want at least 1 failure, got %+v", result.Findings)
		}
	})

	t.Run("bad-no-healthcheck", func(t *testing.T) {
		result := checkDockerfileConventions("testdata/dockerfile-conventions/bad-no-healthcheck")
		if countKind(result, Fail) == 0 {
			t.Fatalf("want at least 1 failure, got %+v", result.Findings)
		}
	})

	t.Run("bad-no-dockerignore", func(t *testing.T) {
		result := checkDockerfileConventions("testdata/dockerfile-conventions/bad-no-dockerignore")
		if countKind(result, Fail) == 0 {
			t.Fatalf("want at least 1 failure, got %+v", result.Findings)
		}
	})

	t.Run("bad-no-user", func(t *testing.T) {
		result := checkDockerfileConventions("testdata/dockerfile-conventions/bad-no-user")
		if countKind(result, Fail) == 0 {
			t.Fatalf("want at least 1 failure, got %+v", result.Findings)
		}
	})

	t.Run("no-dockerfile", func(t *testing.T) {
		result := checkDockerfileConventions("testdata/dockerfile-conventions/no-dockerfile")
		if countKind(result, Skip) == 0 {
			t.Fatalf("want a skip finding, got %+v", result.Findings)
		}
	})
}

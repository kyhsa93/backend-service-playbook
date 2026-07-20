package main

import "testing"

func TestCheckNoDirectEnvAccess(t *testing.T) {
	t.Run("good", func(t *testing.T) {
		result := checkNoDirectEnvAccess("testdata/no-direct-env-access/good")
		if got := countKind(result, Fail); got != 0 {
			t.Fatalf("want 0 failures, got %d: %+v", got, result.Findings)
		}
	})

	t.Run("bad-domain", func(t *testing.T) {
		result := checkNoDirectEnvAccess("testdata/no-direct-env-access/bad-domain")
		if countKind(result, Fail) == 0 {
			t.Fatalf("want at least 1 failure, got %+v", result.Findings)
		}
	})

	t.Run("bad-application", func(t *testing.T) {
		result := checkNoDirectEnvAccess("testdata/no-direct-env-access/bad-application")
		if countKind(result, Fail) == 0 {
			t.Fatalf("want at least 1 failure, got %+v", result.Findings)
		}
	})

	t.Run("good-infra-ignored", func(t *testing.T) {
		// infrastructure/의 os.Getenv 호출은 이 규칙의 대상이 아니다 — domain/, application/만 검사한다.
		result := checkNoDirectEnvAccess("testdata/no-direct-env-access/good-infra-ignored")
		if got := countKind(result, Fail); got != 0 {
			t.Fatalf("want 0 failures, got %d: %+v", got, result.Findings)
		}
	})
}

package main

import (
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

// envAccessCall — os.Getenv(...)/os.LookupEnv(...) 호출. import 별칭으로 os를 다른
// 이름으로 감출 가능성은 이 저장소의 실제 코드에 전례가 없어 다루지 않는다(다른
// 규칙들과 동일하게 실제로 반복되는 패턴만 정밀 타겟팅).
var envAccessCall = regexp.MustCompile(`\bos\.(?:Getenv|LookupEnv)\s*\(`)

// checkNoDirectEnvAccess — [13] no-direct-env-access-outside-config:
// internal/domain/, internal/application/은 os.Getenv/os.LookupEnv를 직접 호출할 수
// 없다 — 환경 변수 검증은 internal/config/(fail-fast)에 모으고, internal/infrastructure/는
// config를 거치지 않는 하위 SDK 초기화(예: AWS SDK가 자체적으로 읽는 값) 정도만
// 예외적으로 허용한다(config.md — "관심사별로 설정을 분리한다").
func checkNoDirectEnvAccess(root string) RuleResult {
	result := RuleResult{Section: "no-direct-env-access-outside-config"}
	found := false
	walkErr := filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() || !strings.HasSuffix(path, ".go") {
			return nil
		}
		if strings.HasSuffix(d.Name(), "_test.go") {
			return nil
		}
		slashPath := filepath.ToSlash(path)
		inDomain := strings.Contains(slashPath, "/internal/domain/")
		inApplication := strings.Contains(slashPath, "/internal/application/")
		if !inDomain && !inApplication {
			return nil
		}
		content, readErr := os.ReadFile(path)
		if readErr != nil {
			return nil
		}
		src := stripGoComments(string(content))
		rel, _ := filepath.Rel(root, path)
		found = true
		if envAccessCall.MatchString(src) {
			result.Findings = append(result.Findings, failFinding(rel,
				"os.Getenv/os.LookupEnv를 직접 호출함 — 환경 변수 검증은 internal/config/(fail-fast)로만 모아야 한다(docs/architecture/config.md)"))
		} else {
			result.Findings = append(result.Findings, passFinding(rel))
		}
		return nil
	})
	if walkErr != nil {
		result.Findings = append(result.Findings, failFinding(root, "디렉토리 탐색 실패: "+walkErr.Error()))
	} else if !found {
		result.Findings = append(result.Findings, skipFinding("internal/domain/, internal/application/ 안에 .go 파일 없음"))
	}
	return result
}

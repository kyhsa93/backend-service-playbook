package main

import (
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

// ifErrNotNil은 `if err != nil {`(공백 변형 포함) 시작 지점을 찾는다. 본문은
// 이 매치 뒤에서 중괄호 카운팅으로 직접 잘라낸다(브레이스가 중첩될 수 있는 함수
// 본문 내부 if문이라 repository_naming.go/no_cross_aggregate_reference.go의
// "다음 컬럼 0 `}`" 근사(top-level 선언 전용)를 쓸 수 없다).
var ifErrNotNil = regexp.MustCompile(`\bif\s+err\s*!=\s*nil\s*\{`)

// checkNoSilentCatch — [17] no-silent-catch: `if err != nil { }`처럼 에러를 확인만
// 하고 반환도, 로깅도, wrap도 하지 않는 완전히 빈 블록을 금지한다(observability.md —
// "에러는 반드시 로깅... 조용히 삼키지 않는다"). Go에는 try/catch가 없어 빈 catch
// 블록이라는 형태 자체가 없지만, 이 패턴이 Go에서의 등가물이다. errcheck(golangci-lint
// 기본 세트)는 "에러를 아예 확인하지 않고 버리는" 경우만 잡고, "확인은 했지만 빈
// 블록으로 버리는" 경우는 잡지 못한다 — 그 갭을 메운다.
//
// 빈 블록만 정밀 타겟팅한다(공백/주석만 있으면 빈 것으로 간주) — "return을 하지
// 않는 모든 case"까지 잡으려 하면 error를 로깅만 하고 계속 진행하는 정상적인 케이스
// (예: best-effort 부가 작업 실패)까지 오탐하므로 다루지 않는다.
func checkNoSilentCatch(root string) RuleResult {
	result := RuleResult{Section: "no-silent-catch"}
	found := false
	walkErr := filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() || !strings.HasSuffix(path, ".go") {
			return nil
		}
		if strings.HasSuffix(d.Name(), "_test.go") {
			return nil
		}
		if !strings.Contains(filepath.ToSlash(path), "/internal/") {
			return nil
		}
		content, readErr := os.ReadFile(path)
		if readErr != nil {
			return nil
		}
		src := stripGoComments(string(content))
		rel, _ := filepath.Rel(root, path)

		matches := ifErrNotNil.FindAllStringIndex(src, -1)
		if len(matches) == 0 {
			return nil
		}
		found = true
		emptyCount := 0
		for _, m := range matches {
			body, ok := extractBraceBody(src, m[1])
			if ok && strings.TrimSpace(body) == "" {
				emptyCount++
			}
		}
		if emptyCount > 0 {
			result.Findings = append(result.Findings, failFinding(rel,
				"if err != nil { } 처럼 완전히 빈 블록으로 에러를 조용히 삼키는 곳이 있음 — 최소한 로깅하거나 반환/wrap해야 한다(docs/architecture/observability.md)"))
		} else {
			result.Findings = append(result.Findings, passFinding(rel))
		}
		return nil
	})
	if walkErr != nil {
		result.Findings = append(result.Findings, failFinding(root, "디렉토리 탐색 실패: "+walkErr.Error()))
	} else if !found {
		result.Findings = append(result.Findings, skipFinding("if err != nil { 없음"))
	}
	return result
}

// extractBraceBody는 이미 열린 `{`(bodyStart는 그 바로 다음 위치) 뒤에서 중괄호 깊이를
// 세어 짝이 맞는 `}`까지의 본문을 잘라낸다. 문자열 리터럴 안의 `{`/`}`는 고려하지
// 않는 텍스트 근사다(이 저장소의 실제 error-handling 코드에는 이 근사를 깨뜨릴 만큼
// 브레이스를 포함한 문자열 리터럴이 if err 블록 바로 안에 오는 경우가 없다).
func extractBraceBody(src string, bodyStart int) (string, bool) {
	depth := 1
	for i := bodyStart; i < len(src); i++ {
		switch src[i] {
		case '{':
			depth++
		case '}':
			depth--
			if depth == 0 {
				return src[bodyStart:i], true
			}
		}
	}
	return "", false
}

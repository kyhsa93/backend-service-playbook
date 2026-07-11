package main

import (
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

// nestjs harness의 cqrs-pattern evaluator(harness/evaluators/rules/cqrs-pattern.evaluator.ts —
// application/query/ 파일이 "Repository" 문자열을 포함하면 FAIL)를 Go로 이식한 것이다.
// Query Handler는 쓰기 전용 Repository 인터페이스가 아니라 읽기 전용 Query
// 인터페이스에만 의존해야 한다(cqrs-pattern.md, #166) — 이 규칙이 없으면 Query
// Handler가 account.Repository를 직접 참조해도(=Save에 접근 가능한 상위
// 인터페이스에 우연히 의존해도) 다른 어떤 규칙도 이를 잡아내지 못한다.
//
// "Repository" 식별자를 단어 경계(\b)로 매칭한다 — 변수명 "repo"나 Query
// 인터페이스 자체의 이름에는 반응하지 않고, `account.Repository` 같은 실제
// 타입 참조만 잡아낸다.
var writeRepositoryRef = regexp.MustCompile(`\bRepository\b`)

// checkCQRSPattern — [9] Query 계층에서 쓰기 전용 Repository 타입 참조 금지 (cqrs-pattern.md)
func checkCQRSPattern(root string) RuleResult {
	result := RuleResult{Section: "cqrs-pattern"}
	internal := filepath.Join(root, "internal")
	if _, err := os.Stat(internal); os.IsNotExist(err) {
		result.Findings = append(result.Findings, skipFinding("internal/ 디렉토리 없음 — 검사 생략"))
		return result
	}

	commandDir := filepath.Join(internal, "application", "command")
	queryDir := filepath.Join(internal, "application", "query")

	if _, err := os.Stat(commandDir); err == nil {
		result.Findings = append(result.Findings, passFinding("internal/application/command/"))
	} else {
		result.Findings = append(result.Findings, failFinding("internal/application/command/", "디렉토리 없음"))
	}

	if _, err := os.Stat(queryDir); os.IsNotExist(err) {
		result.Findings = append(result.Findings, failFinding("internal/application/query/", "디렉토리 없음"))
		return result
	}
	result.Findings = append(result.Findings, passFinding("internal/application/query/"))

	found := false
	filepath.WalkDir(queryDir, func(path string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() || !strings.HasSuffix(path, ".go") || strings.HasSuffix(d.Name(), "_test.go") {
			return nil
		}
		content, readErr := os.ReadFile(path)
		if readErr != nil {
			return nil
		}
		found = true
		rel, _ := filepath.Rel(root, path)
		if writeRepositoryRef.MatchString(string(content)) {
			result.Findings = append(result.Findings, failFinding(rel, "Query 계층에서 쓰기 전용 Repository 타입 참조가 감지됨 — Query Handler는 읽기 전용 Query 인터페이스에만 의존해야 함(cqrs-pattern.md)"))
		} else {
			result.Findings = append(result.Findings, passFinding(rel))
		}
		return nil
	})
	if !found {
		result.Findings = append(result.Findings, skipFinding("application/query/ 안에 .go 파일 없음"))
	}
	return result
}

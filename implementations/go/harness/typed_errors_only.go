package main

import (
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

// checkTypedErrorsOnly — typed-errors-only: 루트 AGENTS.md의 절대 규칙 "에러는 enum으로
// 타입화 — free-form 문자열 금지"를 Go 관용구(sentinel `var ErrXxx = errors.New(...)`를
// `domain/<bc>/errors.go`에 한 번만 선언하고 `errors.Is`로 재사용)로 검사한다.
//
// 이 저장소를 실측한 결과(2026-07) internal/domain/, internal/application/ 어디에도
// errors.New(...)가 <bc>/errors.go 밖에서 호출되는 곳이 없고, fmt.Errorf(...)는 전부
// `%w`로 기존 sentinel(또는 그 체인)을 감싸는 형태다 — 그래서 다음 두 가지만 정밀
// 타겟팅해도 오탐 없이 실제 위반을 잡을 수 있다:
//
//  1. errors.New(...) — 파일명이 정확히 "errors.go"가 아닌 domain/·application/ 파일에서
//     호출되면 FAIL. domain/<bc>/errors.go는 sentinel을 선언하는 유일하게 허용된
//     위치다(예: internal/domain/account/errors.go). application/는애초에 sentinel을
//     새로 선언하지 않고 domain의 것을 재사용해야 하므로, application/ 안의
//     errors.New(...)는 위치를 가리지 않고 전부 FAIL이다.
//  2. fmt.Errorf(...) — 호출 인자에 `%w`가 없으면 FAIL(어떤 typed error도 감싸지 않는
//     free-form 메시지). `%w`가 있으면(예: `fmt.Errorf("close account: %w", err)`처럼
//     기존 typed error를 감싸 컨텍스트만 덧붙이는 관용구) PASS로 취급한다 — 이게 바로
//     "레거시 sentinel을 감싸는 정당한 wrapping을 오탐하지 않는" 지점이다.
var (
	errorsNewCall = regexp.MustCompile(`\berrors\.New\(`)
	fmtErrorfCall = regexp.MustCompile(`\bfmt\.Errorf\(`)
)

func checkTypedErrorsOnly(root string) RuleResult {
	result := RuleResult{Section: "typed-errors-only"}
	found := false
	walkErr := filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() || !strings.HasSuffix(path, ".go") {
			return nil
		}
		name := d.Name()
		if strings.HasSuffix(name, "_test.go") {
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

		var violations []string

		// errors.New(...) — domain/<bc>/errors.go만 예외.
		isCanonicalErrorsFile := inDomain && name == "errors.go"
		if !isCanonicalErrorsFile && errorsNewCall.MatchString(src) {
			if inDomain {
				violations = append(violations, "errors.New(...)를 domain/<bc>/errors.go 밖에서 호출함 — "+
					"sentinel error는 domain/<bc>/errors.go에 한 번만 선언하고 errors.Is로 재사용해야 한다")
			} else {
				violations = append(violations, "errors.New(...)를 application/에서 직접 호출함 — "+
					"새 sentinel을 여기서 선언하지 말고 domain/<bc>/errors.go의 기존 sentinel을 재사용해야 한다")
			}
		}

		// fmt.Errorf(...) — %w로 기존 typed error를 감싸지 않으면 free-form 메시지.
		for _, idx := range fmtErrorfCall.FindAllStringIndex(src, -1) {
			openParen := idx[1] - 1
			args := extractBalancedBlock(src, openParen, '(', ')')
			if !strings.Contains(args, "%w") {
				violations = append(violations, "fmt.Errorf(...)가 %w로 기존 typed error를 감싸지 않는 free-form 메시지를 만듦 — "+
					"에러는 enum/sentinel로 타입화해야 한다(AGENTS.md, free-form 문자열 금지)")
				break // 같은 파일에서 반복 보고하지 않는다
			}
		}

		if len(violations) > 0 {
			result.Findings = append(result.Findings, failFinding(rel, strings.Join(violations, "; ")))
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

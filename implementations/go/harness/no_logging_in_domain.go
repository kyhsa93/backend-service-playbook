package main

import (
	"os"
	"path/filepath"
	"strings"
)

// forbiddenLoggingImports — observability.md: "Domain 레이어에서 로깅하지 않는다."
// 표준 라이브러리 로거(log, log/slog)와 이 저장소가 실제로 검토했던 대표적인 서드파티
// 로깅 라이브러리를 함께 막는다 — Domain은 로깅 자체를 하지 않아야 하므로 어떤
// 로거든 import 자체가 신호다.
var forbiddenLoggingImports = []string{
	"log",
	"log/slog",
	"github.com/sirupsen/logrus",
	"go.uber.org/zap",
	"github.com/rs/zerolog",
}

// checkNoLoggingInDomain — [15] no-logging-in-domain: internal/domain/**/*.go는 로깅
// 라이브러리를 import할 수 없다(observability.md — "Domain 레이어에서 로깅하지 않는
// 이유: Domain은 프레임워크 무의존을 유지한다. 도메인 로직의 결과는 Application
// 레이어에서 로깅한다").
func checkNoLoggingInDomain(root string) RuleResult {
	result := RuleResult{Section: "no-logging-in-domain"}
	found := false
	walkErr := filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() || !strings.HasSuffix(path, ".go") {
			return nil
		}
		if strings.HasSuffix(d.Name(), "_test.go") {
			return nil
		}
		slashPath := filepath.ToSlash(path)
		if !strings.Contains(slashPath, "/internal/domain/") {
			return nil
		}
		rel, _ := filepath.Rel(root, path)
		imports, parseErr := fileImportPaths(path)
		if parseErr != nil {
			found = true
			result.Findings = append(result.Findings, failFinding(rel, "Go 파일 파싱 실패: "+parseErr.Error()))
			return nil
		}
		found = true
		var violated []string
		for _, imp := range imports {
			for _, forbidden := range forbiddenLoggingImports {
				if imp == forbidden {
					violated = append(violated, imp)
					break
				}
			}
		}
		if len(violated) > 0 {
			result.Findings = append(result.Findings, failFinding(rel,
				"domain/ 레이어가 로깅 라이브러리를 import함("+strings.Join(violated, ", ")+") — Domain 레이어에서는 로깅하지 않는다. 도메인 로직의 결과는 Application 레이어가 로깅한다(docs/architecture/observability.md)"))
		} else {
			result.Findings = append(result.Findings, passFinding(rel))
		}
		return nil
	})
	if walkErr != nil {
		result.Findings = append(result.Findings, failFinding(root, "디렉토리 탐색 실패: "+walkErr.Error()))
	} else if !found {
		result.Findings = append(result.Findings, skipFinding("internal/domain/ 없음"))
	}
	return result
}

package main

import (
	"os"
	"path/filepath"
	"strings"
)

// checkHandlerPlacement — [4] Handler 파일 위치: CQRS 핸들러 → application/command|query/, HTTP 핸들러 → interface/http/
func checkHandlerPlacement(root string) RuleResult {
	result := RuleResult{Section: "handler-placement"}
	found := false
	walkErr := filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() || !strings.HasSuffix(path, ".go") {
			return nil
		}
		name := d.Name()
		rel, _ := filepath.Rel(root, path)
		pathSlash := filepath.ToSlash(path)

		if strings.HasSuffix(name, "_handler.go") &&
			!strings.HasSuffix(name, "_event_handler.go") {
			found = true
			// guide.md가 문서화한 HTTP 핸들러 위치(interface/http/)에 있으면
			// CQRS 핸들러(application/command|query/) 규칙 대상에서 제외하고 그대로 통과시킨다.
			switch {
			case strings.Contains(pathSlash, "/interface/http/"):
				result.Findings = append(result.Findings, passFinding(rel+" (HTTP handler)"))
			case strings.Contains(pathSlash, "/application/command/"),
				strings.Contains(pathSlash, "/application/query/"):
				result.Findings = append(result.Findings, passFinding(rel))
			default:
				result.Findings = append(result.Findings, failFinding(rel, "handler 파일은 application/command/, application/query/ 또는 interface/http/ 에 있어야 함"))
			}
		}
		return nil
	})
	if walkErr != nil {
		result.Findings = append(result.Findings, failFinding(root, "디렉토리 탐색 실패: "+walkErr.Error()))
	} else if !found {
		result.Findings = append(result.Findings, skipFinding("handler 파일 없음"))
	}
	return result
}

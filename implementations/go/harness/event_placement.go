package main

import (
	"os"
	"path/filepath"
	"strings"
)

// checkEventPlacement — [7] 이벤트 핸들러·인티그레이션 이벤트 배치
func checkEventPlacement(root string) RuleResult {
	result := RuleResult{Section: "event-placement"}
	found := false
	walkErr := filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() || !strings.HasSuffix(path, ".go") {
			return nil
		}
		name := d.Name()
		rel, _ := filepath.Rel(root, path)
		pathSlash := filepath.ToSlash(path)

		if strings.HasSuffix(name, "_event_handler.go") {
			found = true
			if strings.Contains(pathSlash, "/application/event/") {
				result.Findings = append(result.Findings, passFinding(rel))
			} else {
				result.Findings = append(result.Findings, failFinding(rel, "이벤트 핸들러는 application/event/ 에 있어야 함"))
			}
		}
		if strings.HasSuffix(name, "_integration_event.go") {
			found = true
			if strings.Contains(pathSlash, "/application/integration-event/") {
				result.Findings = append(result.Findings, passFinding(rel))
			} else {
				result.Findings = append(result.Findings, failFinding(rel, "integration event는 application/integration-event/ 에 있어야 함"))
			}
		}
		return nil
	})
	if walkErr != nil {
		result.Findings = append(result.Findings, failFinding(root, "디렉토리 탐색 실패: "+walkErr.Error()))
	} else if !found {
		result.Findings = append(result.Findings, skipFinding("이벤트 핸들러 없음"))
	}
	return result
}

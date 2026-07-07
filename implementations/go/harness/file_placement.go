package main

import (
	"os"
	"path/filepath"
	"strings"
)

// checkFilePlacement — [5] 파일명 suffix 기반 레이어 배치 규칙
func checkFilePlacement(root string) RuleResult {
	result := RuleResult{Section: "file-placement"}
	filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() || !strings.HasSuffix(path, ".go") {
			return nil
		}
		name := d.Name()
		rel, _ := filepath.Rel(root, path)
		pathSlash := filepath.ToSlash(path)

		switch {
		case strings.HasSuffix(name, "_task_controller.go"):
			if strings.Contains(pathSlash, "/interface/") {
				result.Findings = append(result.Findings, passFinding(rel))
			} else {
				result.Findings = append(result.Findings, failFinding(rel, "task_controller 파일은 interface/ 에 있어야 함"))
			}
		case strings.HasSuffix(name, "_scheduler.go"):
			if strings.Contains(pathSlash, "/infrastructure/") {
				result.Findings = append(result.Findings, passFinding(rel))
			} else {
				result.Findings = append(result.Findings, failFinding(rel, "scheduler 파일은 infrastructure/ 에 있어야 함"))
			}
		}
		return nil
	})
	return result
}

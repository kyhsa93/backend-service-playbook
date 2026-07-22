package main

import (
	"os"
	"path/filepath"
	"strings"
)

// checkFilePlacement — [5] layer-placement rule based on filename suffix
func checkFilePlacement(root string) RuleResult {
	result := RuleResult{Section: "file-placement"}
	walkErr := filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
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
				result.Findings = append(result.Findings, failFinding(rel, "a task_controller file must be under interface/"))
			}
		case strings.HasSuffix(name, "_scheduler.go"):
			if strings.Contains(pathSlash, "/infrastructure/") {
				result.Findings = append(result.Findings, passFinding(rel))
			} else {
				result.Findings = append(result.Findings, failFinding(rel, "a scheduler file must be under infrastructure/"))
			}
		}
		return nil
	})
	if walkErr != nil {
		result.Findings = append(result.Findings, failFinding(root, "directory walk failed: "+walkErr.Error()))
	}
	return result
}

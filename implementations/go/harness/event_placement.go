package main

import (
	"os"
	"path/filepath"
	"strings"
)

// checkEventPlacement — [7] event handler / integration event placement
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
				result.Findings = append(result.Findings, failFinding(rel, "an event handler must be under application/event/"))
			}
		}
		if strings.HasSuffix(name, "_integration_event.go") {
			found = true
			if strings.Contains(pathSlash, "/application/integration-event/") {
				result.Findings = append(result.Findings, passFinding(rel))
			} else {
				result.Findings = append(result.Findings, failFinding(rel, "an integration event must be under application/integration-event/"))
			}
		}
		return nil
	})
	if walkErr != nil {
		result.Findings = append(result.Findings, failFinding(root, "directory walk failed: "+walkErr.Error()))
	} else if !found {
		result.Findings = append(result.Findings, skipFinding("no event handlers"))
	}
	return result
}

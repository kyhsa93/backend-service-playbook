package main

import (
	"os"
	"path/filepath"
)

// checkDirectoryStructure — [2] checks the internal/ directory structure (4 layers + CQRS)
func checkDirectoryStructure(root string) RuleResult {
	result := RuleResult{Section: "directory-structure"}
	internal := filepath.Join(root, "internal")
	if _, err := os.Stat(internal); os.IsNotExist(err) {
		result.Findings = append(result.Findings, skipFinding("internal/ directory not found — check skipped"))
		return result
	}
	for _, sub := range []string{"domain", "application", "infrastructure", "interface"} {
		dir := filepath.Join(internal, sub)
		rel, _ := filepath.Rel(root, dir)
		if _, err := os.Stat(dir); err == nil {
			result.Findings = append(result.Findings, passFinding(rel+"/"))
		} else {
			result.Findings = append(result.Findings, failFinding(rel+"/", "directory not found"))
		}
	}
	for _, sub := range []string{"command", "query"} {
		dir := filepath.Join(internal, "application", sub)
		rel, _ := filepath.Rel(root, dir)
		if _, err := os.Stat(dir); err == nil {
			result.Findings = append(result.Findings, passFinding(rel+"/"))
		} else {
			result.Findings = append(result.Findings, failFinding(rel+"/", "directory not found"))
		}
	}
	return result
}

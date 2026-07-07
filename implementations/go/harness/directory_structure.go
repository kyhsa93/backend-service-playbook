package main

import (
	"os"
	"path/filepath"
)

// checkDirectoryStructure — [2] internal/ 디렉토리 구조 검사 (4레이어 + CQRS)
func checkDirectoryStructure(root string) RuleResult {
	result := RuleResult{Section: "directory-structure"}
	internal := filepath.Join(root, "internal")
	if _, err := os.Stat(internal); os.IsNotExist(err) {
		result.Findings = append(result.Findings, skipFinding("internal/ 디렉토리 없음 — 검사 생략"))
		return result
	}
	for _, sub := range []string{"domain", "application", "infrastructure", "interface"} {
		dir := filepath.Join(internal, sub)
		rel, _ := filepath.Rel(root, dir)
		if _, err := os.Stat(dir); err == nil {
			result.Findings = append(result.Findings, passFinding(rel+"/"))
		} else {
			result.Findings = append(result.Findings, failFinding(rel+"/", "디렉토리 없음"))
		}
	}
	for _, sub := range []string{"command", "query"} {
		dir := filepath.Join(internal, "application", sub)
		rel, _ := filepath.Rel(root, dir)
		if _, err := os.Stat(dir); err == nil {
			result.Findings = append(result.Findings, passFinding(rel+"/"))
		} else {
			result.Findings = append(result.Findings, failFinding(rel+"/", "디렉토리 없음"))
		}
	}
	return result
}

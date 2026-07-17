package main

import (
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

var snakeCase = regexp.MustCompile(`^[a-z][a-z0-9]*(_[a-z0-9]+)*\.go$`)

// checkFileNaming — [1] 파일명 snake_case 검사
func checkFileNaming(root string) RuleResult {
	result := RuleResult{Section: "file-naming"}
	found := false
	walkErr := filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() {
			return nil
		}
		if !strings.HasSuffix(path, ".go") {
			return nil
		}
		name := d.Name()
		if strings.HasSuffix(name, "_test.go") || strings.HasSuffix(name, ".pb.go") {
			return nil
		}
		rel, _ := filepath.Rel(root, path)
		found = true
		if snakeCase.MatchString(name) {
			result.Findings = append(result.Findings, passFinding(rel))
		} else {
			result.Findings = append(result.Findings, failFinding(rel, "파일명은 snake_case.go 여야 함"))
		}
		return nil
	})
	if walkErr != nil {
		result.Findings = append(result.Findings, failFinding(root, "디렉토리 탐색 실패: "+walkErr.Error()))
	} else if !found {
		result.Findings = append(result.Findings, skipFinding("Go 파일 없음"))
	}
	return result
}

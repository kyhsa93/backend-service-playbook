package main

import (
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

// 실제 Go interface 타입 선언(예: `type AccountRepository interface {`)만 매칭.
// "type"·"interface"·"Repository" 단어가 파일 어딘가에 각각 등장하는 것만으로는
// (예: struct 정의, 주석, 문자열, import 경로)는 매칭하지 않는다.
var repositoryInterfaceDecl = regexp.MustCompile(`(?m)^\s*type\s+\w*Repository\w*\s+interface\b`)

// 실제 컴파일 타임 인터페이스 검증 선언(예: `var _ account.Repository = (*AccountRepository)(nil)`)만
// 매칭. "var _"·"Repository"·"nil" 세 문자열이 파일 어딘가에 각각 등장하는 것만으로는
// (예: 무관한 변수 선언, 주석, 다른 종류의 검증문) 매칭하지 않는다.
var repositoryImplAssertion = regexp.MustCompile(`(?m)^\s*var\s+_\s+\w+\.\w*Repository\w*\s*=\s*\(\*\w+\)\(nil\)`)

// checkRepositoryPlacement — [3] Repository interface → domain/, 구현체 → infrastructure/
func checkRepositoryPlacement(root string) RuleResult {
	result := RuleResult{Section: "repository-placement"}
	found := false
	filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() || !strings.HasSuffix(path, ".go") {
			return nil
		}
		if strings.HasSuffix(d.Name(), "_test.go") {
			return nil
		}
		content, readErr := os.ReadFile(path)
		if readErr != nil {
			return nil
		}
		rel, _ := filepath.Rel(root, path)
		src := string(content)

		if repositoryInterfaceDecl.MatchString(src) {
			found = true
			if strings.Contains(filepath.ToSlash(path), "/domain/") {
				result.Findings = append(result.Findings, passFinding(rel+" (Repository interface)"))
			} else {
				result.Findings = append(result.Findings, failFinding(rel, "Repository interface는 domain/ 패키지 안에 있어야 함"))
			}
		}

		if repositoryImplAssertion.MatchString(src) {
			found = true
			if strings.Contains(filepath.ToSlash(path), "/infrastructure/") {
				result.Findings = append(result.Findings, passFinding(rel+" (Repository impl 검증)"))
			} else {
				result.Findings = append(result.Findings, failFinding(rel, "Repository 구현체는 infrastructure/ 패키지 안에 있어야 함"))
			}
		}
		return nil
	})
	if !found {
		result.Findings = append(result.Findings, skipFinding("Repository 정의 없음"))
	}
	return result
}

package main

import (
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

// Matches only an actual Go interface type declaration
// (e.g. `type AccountRepository interface {`). It does not match merely
// because the words "type"/"interface"/"Repository" each appear somewhere in
// the file (e.g. in a struct definition, a comment, a string, or an import
// path).
var repositoryInterfaceDecl = regexp.MustCompile(`(?m)^\s*type\s+\w*Repository\w*\s+interface\b`)

// Matches only an actual compile-time interface verification declaration
// (e.g. `var _ account.Repository = (*AccountRepository)(nil)`). It does not
// match merely because the three strings "var _"/"Repository"/"nil" each
// appear somewhere in the file (e.g. in an unrelated variable declaration, a
// comment, or a different kind of assertion).
var repositoryImplAssertion = regexp.MustCompile(`(?m)^\s*var\s+_\s+\w+\.\w*Repository\w*\s*=\s*\(\*\w+\)\(nil\)`)

// checkRepositoryPlacement — [3] Repository interface -> domain/, implementation -> infrastructure/
func checkRepositoryPlacement(root string) RuleResult {
	result := RuleResult{Section: "repository-placement"}
	found := false
	walkErr := filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
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
				result.Findings = append(result.Findings, failFinding(rel, "a Repository interface must be inside the domain/ package"))
			}
		}

		if repositoryImplAssertion.MatchString(src) {
			found = true
			if strings.Contains(filepath.ToSlash(path), "/infrastructure/") {
				result.Findings = append(result.Findings, passFinding(rel+" (Repository impl verified)"))
			} else {
				result.Findings = append(result.Findings, failFinding(rel, "a Repository implementation must be inside the infrastructure/ package"))
			}
		}
		return nil
	})
	if walkErr != nil {
		result.Findings = append(result.Findings, failFinding(root, "directory walk failed: "+walkErr.Error()))
	} else if !found {
		result.Findings = append(result.Findings, skipFinding("no Repository definitions"))
	}
	return result
}

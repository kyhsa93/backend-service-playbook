package main

import (
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

// This is a Go port of the nestjs harness's cqrs-pattern evaluator
// (harness/evaluators/rules/cqrs-pattern.evaluator.ts — FAILs if an
// application/query/ file contains the string "Repository"). A Query Handler
// must depend only on a read-only Query interface, never a write-capable
// Repository interface (cqrs-pattern.md, #166) — without this rule, no other
// rule would catch it if a Query Handler directly referenced
// account.Repository (i.e. happened to depend on a higher interface that has
// access to Save).
//
// Matches the identifier "Repository" on a word boundary (\b) — it does not
// react to a variable name like "repo" or the Query interface's own name, and
// only catches an actual type reference such as `account.Repository`.
var writeRepositoryRef = regexp.MustCompile(`\bRepository\b`)

// checkCQRSPattern — [9] forbids referencing a write-capable Repository type from the Query layer (cqrs-pattern.md)
func checkCQRSPattern(root string) RuleResult {
	result := RuleResult{Section: "cqrs-pattern"}
	internal := filepath.Join(root, "internal")
	if _, err := os.Stat(internal); os.IsNotExist(err) {
		result.Findings = append(result.Findings, skipFinding("internal/ directory not found — check skipped"))
		return result
	}

	commandDir := filepath.Join(internal, "application", "command")
	queryDir := filepath.Join(internal, "application", "query")

	if _, err := os.Stat(commandDir); err == nil {
		result.Findings = append(result.Findings, passFinding("internal/application/command/"))
	} else {
		result.Findings = append(result.Findings, failFinding("internal/application/command/", "directory not found"))
	}

	if _, err := os.Stat(queryDir); os.IsNotExist(err) {
		result.Findings = append(result.Findings, failFinding("internal/application/query/", "directory not found"))
		return result
	}
	result.Findings = append(result.Findings, passFinding("internal/application/query/"))

	found := false
	walkErr := filepath.WalkDir(queryDir, func(path string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() || !strings.HasSuffix(path, ".go") || strings.HasSuffix(d.Name(), "_test.go") {
			return nil
		}
		content, readErr := os.ReadFile(path)
		if readErr != nil {
			return nil
		}
		found = true
		rel, _ := filepath.Rel(root, path)
		if writeRepositoryRef.MatchString(string(content)) {
			result.Findings = append(result.Findings, failFinding(rel, "detected a reference to a write-capable Repository type in the Query layer — a Query Handler must depend only on a read-only Query interface (cqrs-pattern.md)"))
		} else {
			result.Findings = append(result.Findings, passFinding(rel))
		}
		return nil
	})
	if walkErr != nil {
		result.Findings = append(result.Findings, failFinding(queryDir, "directory walk failed: "+walkErr.Error()))
	} else if !found {
		result.Findings = append(result.Findings, skipFinding("no .go files in application/query/"))
	}
	return result
}

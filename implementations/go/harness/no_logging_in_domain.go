package main

import (
	"os"
	"path/filepath"
	"strings"
)

// forbiddenLoggingImports — observability.md: "the Domain layer does not log."
// Blocks both standard library loggers (log, log/slog) and the representative
// third-party logging libraries this repository has actually reviewed —
// Domain must not log at all, so importing any logger is itself the signal.
var forbiddenLoggingImports = []string{
	"log",
	"log/slog",
	"github.com/sirupsen/logrus",
	"go.uber.org/zap",
	"github.com/rs/zerolog",
}

// checkNoLoggingInDomain — [15] no-logging-in-domain: internal/domain/**/*.go
// must not import a logging library (observability.md — "why the Domain layer
// does not log: Domain remains framework-agnostic. The result of domain logic
// is logged by the Application layer").
func checkNoLoggingInDomain(root string) RuleResult {
	result := RuleResult{Section: "no-logging-in-domain"}
	found := false
	walkErr := filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() || !strings.HasSuffix(path, ".go") {
			return nil
		}
		if strings.HasSuffix(d.Name(), "_test.go") {
			return nil
		}
		slashPath := filepath.ToSlash(path)
		if !strings.Contains(slashPath, "/internal/domain/") {
			return nil
		}
		rel, _ := filepath.Rel(root, path)
		imports, parseErr := fileImportPaths(path)
		if parseErr != nil {
			found = true
			result.Findings = append(result.Findings, failFinding(rel, "failed to parse Go file: "+parseErr.Error()))
			return nil
		}
		found = true
		var violated []string
		for _, imp := range imports {
			for _, forbidden := range forbiddenLoggingImports {
				if imp == forbidden {
					violated = append(violated, imp)
					break
				}
			}
		}
		if len(violated) > 0 {
			result.Findings = append(result.Findings, failFinding(rel,
				"the domain/ layer imports a logging library ("+strings.Join(violated, ", ")+") — the Domain layer must not log. The Application layer logs the outcome of domain logic (docs/architecture/observability.md)"))
		} else {
			result.Findings = append(result.Findings, passFinding(rel))
		}
		return nil
	})
	if walkErr != nil {
		result.Findings = append(result.Findings, failFinding(root, "directory walk failed: "+walkErr.Error()))
	} else if !found {
		result.Findings = append(result.Findings, skipFinding("internal/domain/ not found"))
	}
	return result
}

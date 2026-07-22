package main

import (
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

// envAccessCall — calls to os.Getenv(...)/os.LookupEnv(...). The possibility
// of hiding os behind an import alias is not handled, since there is no
// precedent for it in this repository's actual code (as with the other
// rules, only patterns that actually recur are precisely targeted).
var envAccessCall = regexp.MustCompile(`\bos\.(?:Getenv|LookupEnv)\s*\(`)

// checkNoDirectEnvAccess — [13] no-direct-env-access-outside-config:
// internal/domain/ and internal/application/ must not directly call
// os.Getenv/os.LookupEnv — environment variable validation must be
// consolidated in internal/config/ (fail-fast), and internal/infrastructure/
// is exceptionally allowed only for lower-level SDK initialization that
// bypasses config (e.g. values the AWS SDK reads on its own) (config.md —
// "separate configuration by concern").
func checkNoDirectEnvAccess(root string) RuleResult {
	result := RuleResult{Section: "no-direct-env-access-outside-config"}
	found := false
	walkErr := filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() || !strings.HasSuffix(path, ".go") {
			return nil
		}
		if strings.HasSuffix(d.Name(), "_test.go") {
			return nil
		}
		slashPath := filepath.ToSlash(path)
		inDomain := strings.Contains(slashPath, "/internal/domain/")
		inApplication := strings.Contains(slashPath, "/internal/application/")
		if !inDomain && !inApplication {
			return nil
		}
		content, readErr := os.ReadFile(path)
		if readErr != nil {
			return nil
		}
		src := stripGoComments(string(content))
		rel, _ := filepath.Rel(root, path)
		found = true
		if envAccessCall.MatchString(src) {
			result.Findings = append(result.Findings, failFinding(rel,
				"directly calls os.Getenv/os.LookupEnv — env var validation must be consolidated only in internal/config/ (fail-fast) (docs/architecture/config.md)"))
		} else {
			result.Findings = append(result.Findings, passFinding(rel))
		}
		return nil
	})
	if walkErr != nil {
		result.Findings = append(result.Findings, failFinding(root, "directory walk failed: "+walkErr.Error()))
	} else if !found {
		result.Findings = append(result.Findings, skipFinding("no .go files in internal/domain/, internal/application/"))
	}
	return result
}

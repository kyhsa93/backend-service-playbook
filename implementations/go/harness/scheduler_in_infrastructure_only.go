package main

import (
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

// schedulerConstructRef — references to time.Ticker/time.NewTicker and
// robfig/cron-family scheduling constructs. This also catches the cron
// library's import path itself ("robfig/cron"), so renaming the symbol via
// an alias import does not slip through.
var schedulerConstructRef = regexp.MustCompile(`\btime\.Ticker\b|\btime\.NewTicker\b|\brobfig/cron\b|\bcron\.New\b`)

// checkSchedulerInInfrastructureOnly — [16] scheduler-in-infrastructure-only:
// using time.Ticker/time.NewTicker or a cron library is allowed only under
// internal/infrastructure/ (scheduling.md). Since
// internal/infrastructure/outbox/poller.go already legitimately uses Ticker
// (the async Outbox setup), this rule scans only domain/ and application/ to
// block scheduling primitive types from leaking into those two layers.
func checkSchedulerInInfrastructureOnly(root string) RuleResult {
	result := RuleResult{Section: "scheduler-in-infrastructure-only"}
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
		if schedulerConstructRef.MatchString(src) {
			result.Findings = append(result.Findings, failFinding(rel,
				"references a time.Ticker/cron scheduling component — scheduling must be handled only within internal/infrastructure/ (docs/architecture/scheduling.md)"))
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

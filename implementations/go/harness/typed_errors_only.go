package main

import (
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

// checkTypedErrorsOnly — typed-errors-only: checks the root AGENTS.md's absolute
// rule "errors must be typed as an enum — free-form strings are forbidden"
// against the Go idiom (declaring a sentinel `var ErrXxx = errors.New(...)`
// exactly once in `domain/<bc>/errors.go` and reusing it via `errors.Is`).
//
// Having verified this repository's actual state (as of 2026-07), neither
// internal/domain/ nor internal/application/ has an errors.New(...) call
// outside of <bc>/errors.go anywhere, and every fmt.Errorf(...) call wraps an
// existing sentinel (or a chain of them) with `%w` — so precisely targeting
// only the following two patterns catches real violations without false
// positives:
//
//  1. errors.New(...) — FAIL if called from a domain/ or application/ file
//     whose name is not exactly "errors.go". domain/<bc>/errors.go is the
//     only allowed location for declaring sentinels (e.g.
//     internal/domain/account/errors.go). application/ must never declare a
//     new sentinel of its own and must instead reuse the domain's, so any
//     errors.New(...) inside application/ is a FAIL regardless of location.
//  2. fmt.Errorf(...) — FAIL if the call's arguments contain no `%w` (a
//     free-form message that wraps no typed error at all). If `%w` is present
//     (e.g. `fmt.Errorf("close account: %w", err)`, the idiom of wrapping an
//     existing typed error while adding context), treat it as PASS — this is
//     exactly the point that avoids a false positive on legitimately wrapping
//     a legacy sentinel.
var (
	errorsNewCall = regexp.MustCompile(`\berrors\.New\(`)
	fmtErrorfCall = regexp.MustCompile(`\bfmt\.Errorf\(`)
)

func checkTypedErrorsOnly(root string) RuleResult {
	result := RuleResult{Section: "typed-errors-only"}
	found := false
	walkErr := filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() || !strings.HasSuffix(path, ".go") {
			return nil
		}
		name := d.Name()
		if strings.HasSuffix(name, "_test.go") {
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

		var violations []string

		// errors.New(...) — the only exception is domain/<bc>/errors.go.
		isCanonicalErrorsFile := inDomain && name == "errors.go"
		if !isCanonicalErrorsFile && errorsNewCall.MatchString(src) {
			if inDomain {
				violations = append(violations, "calls errors.New(...) outside domain/<bc>/errors.go — "+
					"a sentinel error must be declared exactly once in domain/<bc>/errors.go and reused via errors.Is")
			} else {
				violations = append(violations, "calls errors.New(...) directly from application/ — "+
					"it must not declare a new sentinel here, and must instead reuse an existing sentinel from domain/<bc>/errors.go")
			}
		}

		// fmt.Errorf(...) — a free-form message unless it wraps an existing typed error with %w.
		for _, idx := range fmtErrorfCall.FindAllStringIndex(src, -1) {
			openParen := idx[1] - 1
			args := extractBalancedBlock(src, openParen, '(', ')')
			if !strings.Contains(args, "%w") {
				violations = append(violations, "fmt.Errorf(...) builds a free-form message that does not wrap an existing typed error with %w — "+
					"errors must be typed as an enum/sentinel (AGENTS.md, free-form strings are forbidden)")
				break // do not report the same file repeatedly
			}
		}

		if len(violations) > 0 {
			result.Findings = append(result.Findings, failFinding(rel, strings.Join(violations, "; ")))
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

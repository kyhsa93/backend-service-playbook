package main

import (
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

// ifErrNotNil finds the start of `if err != nil {` (including whitespace
// variants). The body is cut out directly after this match by counting
// braces (since this is an if statement inside a function body where braces
// can nest, the "next column-0 `}`" approximation from
// repository_naming.go/no_cross_aggregate_reference.go, which only works for
// top-level declarations, cannot be used).
var ifErrNotNil = regexp.MustCompile(`\bif\s+err\s*!=\s*nil\s*\{`)

// checkNoSilentCatch — [17] no-silent-catch: forbids a completely empty block
// like `if err != nil { }` that merely checks the error without returning,
// logging, or wrapping it (observability.md — "errors must be logged... never
// silently swallowed"). Go has no try/catch, so there is no such thing as an
// empty catch block per se, but this pattern is Go's equivalent. errcheck
// (part of golangci-lint's default set) only catches the case of "not
// checking the error at all", not "checking it but discarding it in an empty
// block" — this fills that gap.
//
// Only empty blocks are precisely targeted (whitespace/comments-only counts
// as empty) — trying to also catch "every case that doesn't return" would
// flag legitimate cases that only log the error and continue (e.g. a
// best-effort side task failing), so those are left alone.
func checkNoSilentCatch(root string) RuleResult {
	result := RuleResult{Section: "no-silent-catch"}
	found := false
	walkErr := filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() || !strings.HasSuffix(path, ".go") {
			return nil
		}
		if strings.HasSuffix(d.Name(), "_test.go") {
			return nil
		}
		if !strings.Contains(filepath.ToSlash(path), "/internal/") {
			return nil
		}
		content, readErr := os.ReadFile(path)
		if readErr != nil {
			return nil
		}
		src := stripGoComments(string(content))
		rel, _ := filepath.Rel(root, path)

		matches := ifErrNotNil.FindAllStringIndex(src, -1)
		if len(matches) == 0 {
			return nil
		}
		found = true
		emptyCount := 0
		for _, m := range matches {
			body, ok := extractBraceBody(src, m[1])
			if ok && strings.TrimSpace(body) == "" {
				emptyCount++
			}
		}
		if emptyCount > 0 {
			result.Findings = append(result.Findings, failFinding(rel,
				"there is a spot that silently swallows an error with a completely empty block like if err != nil { } — it must at least be logged or returned/wrapped (docs/architecture/observability.md)"))
		} else {
			result.Findings = append(result.Findings, passFinding(rel))
		}
		return nil
	})
	if walkErr != nil {
		result.Findings = append(result.Findings, failFinding(root, "directory walk failed: "+walkErr.Error()))
	} else if !found {
		result.Findings = append(result.Findings, skipFinding("no if err != nil { blocks"))
	}
	return result
}

// extractBraceBody cuts out the body up to the matching `}` by counting brace
// depth after an already-opened `{` (bodyStart is the position right after
// it). This is a text approximation that does not account for `{`/`}` inside
// string literals (this repository's actual error-handling code never has a
// brace-containing string literal directly inside an if-err block that would
// break this approximation).
func extractBraceBody(src string, bodyStart int) (string, bool) {
	depth := 1
	for i := bodyStart; i < len(src); i++ {
		switch src[i] {
		case '{':
			depth++
		case '}':
			depth--
			if depth == 0 {
				return src[bodyStart:i], true
			}
		}
	}
	return "", false
}

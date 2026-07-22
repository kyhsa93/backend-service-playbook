package main

import (
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

// checkRateLimitWired — rate-limit-wired: checks that the rate limit
// middleware required by docs/architecture/rate-limiting.md isn't just
// defined but is actually registered at a router-assembly point (router.go,
// main.go, etc.) — this exists to catch the regression where it's "built but
// never called by anyone" and ends up as dead code.
//
// It first finds a top-level (or receiver-having) declaration whose
// function/method name contains "RateLimit" and locates its definition, then
// searches the rest of the source — excluding that definition file and test
// files — for a reference that calls the same symbol (including a
// package-prefixed form like `RateLimit(` or `middleware.RateLimit(`). The
// definition file itself is naturally excluded from the search target,
// because the `func RateLimit(...)` declaration itself would match
// "RateLimit(" and be mistaken for a "call" of its own declaration. A
// reference found only in test files is not considered "actually wired into
// the router" (what this rule verifies is production wiring).
var rateLimitFuncDecl = regexp.MustCompile(`(?m)^func\s+(?:\(\s*\w+\s+\*?\w+\s*\)\s+)?(\w*RateLimit\w*)\s*\(`)

func checkRateLimitWired(root string) RuleResult {
	result := RuleResult{Section: "rate-limit-wired"}

	type decl struct {
		file string
		name string
	}
	var decls []decl
	walkErr := filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() || !strings.HasSuffix(path, ".go") {
			return nil
		}
		if strings.HasSuffix(d.Name(), "_test.go") {
			return nil
		}
		// A function outside middleware/ (e.g. LoadRateLimitConfig in
		// internal/config/rate_limit.go) must not be treated as a middleware
		// candidate just because its name contains "RateLimit" — the actual
		// HTTP middleware constructor must live under interface/http/middleware/
		// (layer-architecture.md, rate-limiting.md).
		if !strings.Contains(filepath.ToSlash(path), "/middleware/") {
			return nil
		}
		content, readErr := os.ReadFile(path)
		if readErr != nil {
			return nil
		}
		src := stripGoComments(string(content))
		for _, m := range rateLimitFuncDecl.FindAllStringSubmatch(src, -1) {
			decls = append(decls, decl{file: path, name: m[1]})
		}
		return nil
	})
	if walkErr != nil {
		result.Findings = append(result.Findings, failFinding(root, "directory walk failed: "+walkErr.Error()))
		return result
	}
	if len(decls) == 0 {
		result.Findings = append(result.Findings, skipFinding("no RateLimit middleware function/method — docs/architecture/rate-limiting.md"))
		return result
	}

	definingFiles := make(map[string]bool, len(decls))
	for _, dl := range decls {
		definingFiles[dl.file] = true
	}

	for _, dl := range decls {
		callPattern := regexp.MustCompile(`(?:\w+\.)?` + regexp.QuoteMeta(dl.name) + `\s*\(`)
		wired := false
		walkErr := filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
			if err != nil || d.IsDir() || wired || !strings.HasSuffix(path, ".go") {
				return nil
			}
			if strings.HasSuffix(d.Name(), "_test.go") || definingFiles[path] {
				return nil
			}
			content, readErr := os.ReadFile(path)
			if readErr != nil {
				return nil
			}
			if callPattern.MatchString(stripGoComments(string(content))) {
				wired = true
			}
			return nil
		})
		if walkErr != nil {
			result.Findings = append(result.Findings, failFinding(root, "directory walk failed: "+walkErr.Error()))
			continue
		}

		rel, _ := filepath.Rel(root, dl.file)
		if wired {
			result.Findings = append(result.Findings, passFinding(rel+" ("+dl.name+" — wired into the router)"))
		} else {
			result.Findings = append(result.Findings, failFinding(rel+" ("+dl.name+")",
				"the rate limit middleware is only defined and never called anywhere (excluding test files) — "+
					"it must actually be registered at an assembly point such as router.go/main.go (docs/architecture/rate-limiting.md)"))
		}
	}

	return result
}

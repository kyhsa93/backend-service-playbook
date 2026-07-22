package main

import (
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

// checkRepositoryNaming — checks whether Repository/Query interface method
// naming follows the find<Noun>s/save<Noun>/delete<Noun> convention from the
// root docs/architecture/repository-pattern.md.
//
// This is limited to Repository/Query interface declarations in the domain/
// layer — implementations under infrastructure/ are out of scope, since they
// must be free to have private/internal helper methods
// (repository_placement.go distinguishes domain/interface vs.
// infrastructure/impl the same way).
//
// The approach is a blocklist — a grammar that catches "anything that isn't
// find<Noun>s" would also flag legitimate methods like
// HasTransactionWithReference as false positives. Instead, only anti-patterns
// that have actually recurred (FindByID-style, FindAll, Count*, bare
// Save/Delete, Update*) are precisely targeted. Update* is explicitly
// forbidden by repository-pattern.md — state changes must go through a
// domain method on the Aggregate after a query, not a separate Repository
// update method.
var (
	// type XRepository interface { ... } / type XQuery interface { ... } — extends
	// the same idiom as repositoryInterfaceDecl in repository_placement.go to Query as well.
	repoOrQueryInterfaceDecl = regexp.MustCompile(`(?m)^\s*type\s+(\w*(?:Repository|Query)\w*)\s+interface\s*\{`)
	// A single method signature line inside an interface body. A line with
	// just an embedded interface name (e.g. `Query`) has no trailing '(' and
	// so is not matched.
	interfaceMethodLine = regexp.MustCompile(`(?m)^\s*(\w+)\s*\(`)

	findByPattern = regexp.MustCompile(`^FindBy\w*$`)
	countPattern  = regexp.MustCompile(`^Count\w*$`)
	updatePattern = regexp.MustCompile(`^Update\w*$`)
)

// repositoryNamingViolation returns the reason for a single violating method
// name. Returns an empty string if it is not a violation.
func repositoryNamingViolation(method string) string {
	switch {
	case findByPattern.MatchString(method):
		return "a dedicated single-record/conditional lookup method (" + method + ") — queries must always be unified into a single find<Noun>s and narrowed with filters (docs/architecture/repository-pattern.md)"
	case method == "FindAll":
		return "FindAll with no noun — the target must be specified in the form find<Noun>s (docs/architecture/repository-pattern.md)"
	case countPattern.MatchString(method):
		return "a separate Count method (" + method + ") — the count must be returned together with the find<Noun>s result, not via a separate method (docs/architecture/repository-pattern.md)"
	case updatePattern.MatchString(method):
		return "an update method on the Repository (" + method + ") — a state change must be performed via the Aggregate's domain method after loading it, then persisted with save<Noun> (docs/architecture/repository-pattern.md)"
	case method == "Save":
		return "Save with no noun — the target must be specified in the form save<Noun> (docs/architecture/repository-pattern.md)"
	case method == "Delete":
		return "Delete with no noun — the target must be specified in the form delete<Noun> (docs/architecture/repository-pattern.md)"
	default:
		return ""
	}
}

// extractInterfaceBody cuts the body from right after `interface {` to the
// next `}` starting at column 0. This repository's Repository/Query interface
// declarations are always top-level (no indentation) and method signatures
// have no nested `{}` (interface method declarations have no body), so this
// approximation is sufficient (the same trade-off as the text-search-based
// approximation in outbox_drain_order.go).
func extractInterfaceBody(src string, bodyStart int) string {
	rest := src[bodyStart:]
	if idx := strings.Index(rest, "\n}"); idx != -1 {
		return rest[:idx]
	}
	return rest
}

// checkRepositoryNaming — checks whether the method names of domain/
// Repository/Query interfaces follow the find<Noun>s/save<Noun>/delete<Noun> convention.
func checkRepositoryNaming(root string) RuleResult {
	result := RuleResult{Section: "repository-naming"}
	found := false
	walkErr := filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() || !strings.HasSuffix(path, ".go") {
			return nil
		}
		if strings.HasSuffix(d.Name(), "_test.go") {
			return nil
		}
		slashPath := filepath.ToSlash(path)
		if !strings.Contains(slashPath, "/domain/") {
			return nil
		}
		content, readErr := os.ReadFile(path)
		if readErr != nil {
			return nil
		}
		rel, _ := filepath.Rel(root, path)
		src := string(content)

		matches := repoOrQueryInterfaceDecl.FindAllStringSubmatchIndex(src, -1)
		for _, m := range matches {
			ifaceName := src[m[2]:m[3]]
			bodyStart := m[1] // match end position = right after "interface {"
			body := extractInterfaceBody(src, bodyStart)

			methodMatches := interfaceMethodLine.FindAllStringSubmatch(body, -1)
			violated := false
			for _, mm := range methodMatches {
				method := mm[1]
				if reason := repositoryNamingViolation(method); reason != "" {
					found = true
					violated = true
					result.Findings = append(result.Findings, failFinding(
						rel+" ("+ifaceName+"."+method+")", reason))
				}
			}
			if !violated {
				found = true
				result.Findings = append(result.Findings, passFinding(rel+" ("+ifaceName+")"))
			}
		}
		return nil
	})
	if walkErr != nil {
		result.Findings = append(result.Findings, failFinding(root, "directory walk failed: "+walkErr.Error()))
	} else if !found {
		result.Findings = append(result.Findings, skipFinding("no domain/ Repository/Query interfaces"))
	}
	return result
}

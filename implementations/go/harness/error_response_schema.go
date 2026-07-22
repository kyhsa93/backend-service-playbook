package main

import (
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
)

// checkErrorResponseSchema — error-response-schema: docs/architecture/error-handling.md
// mandates that every error response has exactly 4 fields (statusCode/code/message/error)
// ({"statusCode":404,"code":"ORDER_NOT_FOUND","message":"...","error":"Not Found"}).
//
// Among struct declarations under internal/interface/http/**, any with a
// `json:"statusCode"` tag is treated as a candidate "error response struct" —
// checking every DTO (CreateAccountRequest, etc.) for "must have exactly 4
// fields" would produce a flood of false positives on completely unrelated,
// legitimate DTOs, so the target is narrowed to only structs with a statusCode
// field. A candidate struct's set of json tags must match exactly
// {statusCode, code, message, error} — having more (e.g. an added timestamp
// field), fewer (e.g. a missing error field), or differently named fields
// (e.g. errorCode) are all violations.
//
// Because the middleware package importing the interface/http package would
// create a circular dependency in this repository (avoiding cycles,
// module-pattern.md), rate_limit_middleware.go keeps its own
// rateLimitErrorResponse that duplicates the same schema separately — this
// rule inspects every struct under internal/interface/** independently
// regardless of the struct's location or name, so this "duplicated struct with
// the same schema" pattern is naturally covered too.
var (
	structDecl  = regexp.MustCompile(`(?ms)^type\s+(\w+)\s+struct\s*\{`)
	jsonTagLine = regexp.MustCompile(`json:"([^"]*)"`)
)

var wantErrorResponseFields = []string{"statusCode", "code", "message", "error"}

func checkErrorResponseSchema(root string) RuleResult {
	result := RuleResult{Section: "error-response-schema"}
	found := false
	walkErr := filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() || !strings.HasSuffix(path, ".go") {
			return nil
		}
		if strings.HasSuffix(d.Name(), "_test.go") {
			return nil
		}
		slashPath := filepath.ToSlash(path)
		if !strings.Contains(slashPath, "/internal/interface/") {
			return nil
		}
		content, readErr := os.ReadFile(path)
		if readErr != nil {
			return nil
		}
		src := stripGoComments(string(content))
		rel, _ := filepath.Rel(root, path)

		matches := structDecl.FindAllStringSubmatchIndex(src, -1)
		for _, m := range matches {
			name := src[m[2]:m[3]]
			bodyStart := m[1] - 1 // position of '{'
			body := extractBalancedBlock(src, bodyStart, '{', '}')

			tagMatches := jsonTagLine.FindAllStringSubmatch(body, -1)
			var fields []string
			hasStatusCode := false
			for _, tm := range tagMatches {
				key := strings.Split(tm[1], ",")[0]
				if key == "-" || key == "" {
					continue
				}
				if key == "statusCode" {
					hasStatusCode = true
				}
				fields = append(fields, key)
			}
			if !hasStatusCode {
				continue // not a candidate error response struct (no statusCode field)
			}
			found = true

			if reason := errorResponseSchemaViolation(fields); reason != "" {
				result.Findings = append(result.Findings, failFinding(rel+" ("+name+")", reason))
			} else {
				result.Findings = append(result.Findings, passFinding(rel+" ("+name+")"))
			}
		}
		return nil
	})
	if walkErr != nil {
		result.Findings = append(result.Findings, failFinding(root, "directory walk failed: "+walkErr.Error()))
	} else if !found {
		result.Findings = append(result.Findings, skipFinding("no error-response struct with a json:\"statusCode\" tag"))
	}
	return result
}

// errorResponseSchemaViolation checks whether fields matches wantErrorResponseFields
// exactly (order does not matter, but both count and names must match). Returns
// an empty string if there is no violation.
func errorResponseSchemaViolation(fields []string) string {
	want := append([]string(nil), wantErrorResponseFields...)
	sort.Strings(want)
	got := append([]string(nil), fields...)
	sort.Strings(got)

	if len(got) == len(want) {
		match := true
		for i := range got {
			if got[i] != want[i] {
				match = false
				break
			}
		}
		if match {
			return ""
		}
	}

	missing := diffFields(want, got)
	extra := diffFields(got, want)
	reason := "the json field composition differs from the standard error-response schema (statusCode/code/message/error)"
	if len(missing) > 0 {
		reason += " — missing: " + strings.Join(missing, ", ")
	}
	if len(extra) > 0 {
		reason += " — extra/misnamed: " + strings.Join(extra, ", ")
	}
	return reason + "(docs/architecture/error-handling.md)"
}

// diffFields returns the elements present in a but not in b (duplicates ignored).
func diffFields(a, b []string) []string {
	inB := make(map[string]bool, len(b))
	for _, x := range b {
		inB[x] = true
	}
	seen := make(map[string]bool)
	var out []string
	for _, x := range a {
		if !inB[x] && !seen[x] {
			out = append(out, x)
			seen[x] = true
		}
	}
	return out
}

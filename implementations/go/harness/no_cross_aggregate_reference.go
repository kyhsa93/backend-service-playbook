package main

import (
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

// checkNoCrossAggregateReference — [12] no-cross-aggregate-reference:
// domain-service.md — "one Aggregate must not directly reference another
// Aggregate" (referencing by ID string is allowed, referencing by struct type
// is forbidden).
//
// The only real case in this repository where two different Aggregates
// coexist within the same Bounded Context is internal/domain/payment/
// (Payment, Refund), so this rule targets exactly those two files (checking
// that payment.go does not have a Refund-typed struct field, and refund.go
// does not have a Payment-typed struct field). A generalized "Aggregate
// detection" heuristic (e.g. inferring Aggregate-ness purely from file naming
// rules) would carry a high risk of false positives on other BCs/files, so
// for now this precisely targets only this known real case — as long as both
// Payment and Refund exist, this is sufficient as a regression guard.
type crossAggregateCheck struct {
	file          string // path relative to the BC root
	aggregateName string
	forbiddenType string
}

var crossAggregateChecks = []crossAggregateCheck{
	{file: "payment.go", aggregateName: "Payment", forbiddenType: "Refund"},
	{file: "refund.go", aggregateName: "Refund", forbiddenType: "Payment"},
}

// checkNoCrossAggregateReference — locates the payment BC directory and checks the two files.
func checkNoCrossAggregateReference(root string) RuleResult {
	result := RuleResult{Section: "no-cross-aggregate-reference"}
	paymentDir := findDirNamed(root, "payment", "/internal/domain/")
	if paymentDir == "" {
		result.Findings = append(result.Findings, skipFinding("internal/domain/payment/ not found (no BC with coexisting Payment/Refund)"))
		return result
	}

	found := false
	for _, check := range crossAggregateChecks {
		path := filepath.Join(paymentDir, check.file)
		content, err := os.ReadFile(path)
		if err != nil {
			continue // silently skip if this BC does not use the Payment/Refund naming as-is
		}
		found = true
		rel, _ := filepath.Rel(root, path)
		src := string(content)

		structBody, ok := extractStructBody(src, check.aggregateName)
		if !ok {
			result.Findings = append(result.Findings, failFinding(rel, "could not find a type "+check.aggregateName+" struct declaration"))
			continue
		}
		if structFieldTypeRef(check.forbiddenType).MatchString(structBody) {
			result.Findings = append(result.Findings, failFinding(rel,
				check.aggregateName+" Aggregate directly references the "+check.forbiddenType+" Aggregate as a struct field — an Aggregate must reference another Aggregate only by its ID string (domain-service.md)"))
		} else {
			result.Findings = append(result.Findings, passFinding(rel+" ("+check.aggregateName+")"))
		}
	}
	if !found {
		result.Findings = append(result.Findings, skipFinding("payment.go/refund.go not found"))
	}
	return result
}

// structFieldTypeRef matches only struct field declaration lines whose type is
// exactly typeName (including pointer/slice forms) — it does not match cases
// like "RefundID string" where the type name happens to appear inside the
// field name (the type itself is string). A field declaration has the shape
// `<field name> <type>`, and the type column must be exactly typeName.
func structFieldTypeRef(typeName string) *regexp.Regexp {
	return regexp.MustCompile(`(?m)^\s*\w+\s+(?:\*|\[\]\*?)?` + regexp.QuoteMeta(typeName) + `\b`)
}

// extractStructBody cuts the body from right after `type <name> struct {` to
// the next `}` at column 0 (the same approximation as extractInterfaceBody in
// repository_naming.go — this repository's Aggregate struct field
// declarations have no nested `{}`).
func extractStructBody(src, typeName string) (string, bool) {
	decl := regexp.MustCompile(`(?m)^type\s+` + regexp.QuoteMeta(typeName) + `\s+struct\s*\{`)
	loc := decl.FindStringIndex(src)
	if loc == nil {
		return "", false
	}
	return extractInterfaceBody(src, loc[1]), true
}

// findDirNamed finds and returns the first directory under root whose path
// contains pathContains and whose name is name (empty string if none found).
func findDirNamed(root, name, pathContains string) string {
	var found string
	_ = filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
		if err != nil || !d.IsDir() || found != "" {
			return nil
		}
		slashPath := filepath.ToSlash(path)
		if d.Name() == name && strings.Contains(slashPath+"/", pathContains) {
			found = path
		}
		return nil
	})
	return found
}

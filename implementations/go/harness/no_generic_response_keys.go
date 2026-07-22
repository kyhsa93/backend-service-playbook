package main

import (
	"os"
	"path/filepath"
	"strings"
)

// forbiddenResponseKeys — from root docs/architecture/api-response.md, "list query
// response format": the key name of a list response must be the plural of the
// domain object name (orders/accounts/payments), and generic keys like
// result/data/items are forbidden.
var forbiddenResponseKeys = map[string]bool{
	"result": true,
	"data":   true,
	"items":  true,
}

// checkNoGenericResponseKeys — no-generic-response-keys: among struct declarations
// under internal/interface/http/**, finds fields whose json tag is exactly
// "result"/"data"/"items". These generic keys must be replaced with the plural
// of the domain object name (orders/accounts/payments, etc.).
//
// Like error_response_schema.go, this structurally parses struct declarations +
// json tags (extractBalancedBlock cuts out the struct body, then jsonTagLine
// extracts the tags) — both rules reuse the same package's structDecl/jsonTagLine
// regexes.
//
// "items" also appears in the root document's "single-resource query response
// format" example as a field name for a sub-list belonging to one resource
// (e.g. an order's line items), but even that example only shows that the
// "no generic keys" principle applies to top-level list responses — it does
// not carve out an exception for the name "items" itself. This repository's
// actual DTOs (GetPaymentsResponse.Payments, GetRefundsResponse.Refunds,
// GetTransactionsResponse.Transactions, etc.) already all use domain plurals,
// so if a sub-list like "items" is ever needed, it too must use a domain noun
// (e.g. orderItems) — a blanket ban regardless of field name is the intent of
// this rule.
func checkNoGenericResponseKeys(root string) RuleResult {
	result := RuleResult{Section: "no-generic-response-keys"}
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
			var bad []string
			for _, tm := range tagMatches {
				key := strings.Split(tm[1], ",")[0]
				if forbiddenResponseKeys[key] {
					bad = append(bad, key)
				}
			}
			if len(bad) == 0 {
				continue
			}
			found = true
			result.Findings = append(result.Findings, failFinding(rel+" ("+name+")",
				"the list-response field uses a generic key ("+strings.Join(bad, ", ")+") — it must use the plural of the domain object name (orders/accounts/payments, etc.) (docs/architecture/api-response.md)"))
		}
		return nil
	})
	if walkErr != nil {
		result.Findings = append(result.Findings, failFinding(root, "directory walk failed: "+walkErr.Error()))
		return result
	}
	if !found {
		result.Findings = append(result.Findings, passFinding("no generic keys (result/data/items) in response structs under internal/interface/**"))
	}
	return result
}

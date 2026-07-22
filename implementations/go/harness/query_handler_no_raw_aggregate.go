package main

import (
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

// handleMethodSig matches a Query Handler's `Handle` method declaration and
// captures the success-branch return type (the first element of the
// `(<type>, error)` tuple). Handler methods in this repo always follow the
// `func (h *XHandler) Handle(ctx context.Context, q XQuery) (<ReturnType>, error) {`
// shape (see get_account_handler.go etc. and the create-domain scaffolding
// template's Get{{.Domain}}Handler), so a signature-line regex is enough —
// no need to parse the full method body.
var handleMethodSig = regexp.MustCompile(`(?m)^func\s+\([^)]+\)\s+Handle\([^)]*\)\s*\(([^,]+),\s*error\)`)

// domainQualifiedPointer matches a pointer type qualified by a package alias,
// e.g. `*account.Account` — the shape a raw domain Aggregate return type
// always takes from application/query (Aggregates are never dot-imported or
// referenced unqualified across packages in this repo).
var domainQualifiedPointer = regexp.MustCompile(`^\*(\w+)\.(\w+)$`)

// checkQueryHandlerNoRawAggregate — query-handler-no-raw-aggregate:
// docs/architecture/api-response.md, "Result object design" — a Query
// Service (Handler) must return a Result/DTO object and must not return the
// domain Aggregate directly ("an Aggregate carries business logic and
// internal state. Serializing it exposes the internal implementation to the
// outside").
//
// This treats it as a failure when the type each Handle method in
// internal/application/query/*.go returns on success is a pointer type
// qualified by the internal/domain/<bc> package that file imports (e.g.
// *account.Account). Rather than hardcoding specific type names
// (Account/Card/Payment/Refund), it uses the general rule "any pointer type
// qualified by a domain package is a violation" — the only legitimate type a
// Query Handler can return is always a dedicated Result type in the same
// query package as that Handler (e.g. GetAccountResult, with no package
// qualifier), and this repository has no pattern of returning another
// domain package's Value Object as-is either — which is why this
// hardcoding-free form generalizes cleanly to new domains too (e.g. the
// create-domain scaffolding). A Command Handler (e.g. the pattern where
// Create{{.Domain}}Handler returns the just-created Aggregate as-is) is not
// covered by this rule — the root document forbids exposing the Aggregate
// directly only for Query responses.
func checkQueryHandlerNoRawAggregate(root string) RuleResult {
	result := RuleResult{Section: "query-handler-no-raw-aggregate"}
	queryDir := filepath.Join(root, "internal", "application", "query")
	if _, err := os.Stat(queryDir); os.IsNotExist(err) {
		result.Findings = append(result.Findings, skipFinding("internal/application/query/ not found"))
		return result
	}

	found := false
	walkErr := filepath.WalkDir(queryDir, func(path string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() || !strings.HasSuffix(path, ".go") || strings.HasSuffix(d.Name(), "_test.go") {
			return nil
		}
		content, readErr := os.ReadFile(path)
		if readErr != nil {
			return nil
		}
		rel, _ := filepath.Rel(root, path)
		src := stripGoComments(string(content))

		sigMatches := handleMethodSig.FindAllStringSubmatch(src, -1)
		if len(sigMatches) == 0 {
			return nil // this file has no Handle method (e.g. result.go)
		}

		imports, parseErr := fileImportPaths(path)
		if parseErr != nil {
			found = true
			result.Findings = append(result.Findings, failFinding(rel, "failed to parse Go file: "+parseErr.Error()))
			return nil
		}
		domainAliases := map[string]bool{}
		for _, imp := range imports {
			if m := domainImportBC.FindStringSubmatch("/" + filepath.ToSlash(imp)); m != nil {
				domainAliases[m[1]] = true
			}
		}

		for _, sig := range sigMatches {
			found = true
			returnType := strings.TrimSpace(sig[1])
			m := domainQualifiedPointer.FindStringSubmatch(returnType)
			if m != nil && domainAliases[m[1]] {
				result.Findings = append(result.Findings, failFinding(rel,
					"Handle() returns a raw domain-package type ("+returnType+") as-is — it must return a dedicated Result/DTO type (docs/architecture/api-response.md)"))
			} else {
				result.Findings = append(result.Findings, passFinding(rel+" (Handle → "+returnType+")"))
			}
		}
		return nil
	})
	if walkErr != nil {
		result.Findings = append(result.Findings, failFinding(queryDir, "directory walk failed: "+walkErr.Error()))
	} else if !found {
		result.Findings = append(result.Findings, skipFinding("no Handle methods in application/query/"))
	}
	return result
}

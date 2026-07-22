package main

import (
	"os"
	"path/filepath"
	"strings"
)

// checkNoCrossBCDomainImport — no-cross-bc-domain-import: from the root
// docs/architecture/tactical-ddd.md, "another Aggregate may only be referenced
// by ID (referencing by object is forbidden)" — this principle applies not
// only between Aggregates within the same Bounded Context
// (no_cross_aggregate_reference.go precisely checks only Payment<->Refund
// inside payment/) but equally between different BCs. domain_layer_isolation.go
// (R1) only checks that domain/ does not import a higher layer
// (application/infrastructure/interface), and no_cross_aggregate_reference.go
// (R5) only looks inside the payment BC — no rule blocks an import between
// different BCs' domain packages, such as `internal/domain/card` directly
// importing `internal/domain/payment`.
//
// It is a violation if any path imported by a file under
// internal/domain/<bc>/*.go is internal/domain/<other-bc> (a BC other than
// itself). A well-formed cross-BC reference is made only through an ID string
// (e.g. `PaymentID string`), so there is no need to import another BC's
// domain package in the first place — the Adapter (infrastructure/acl)
// approach mandated by cross-domain-communication.md is also the
// responsibility of the application/infrastructure layer, not the domain
// layer.
//
// internal/common (shared Value Objects like Money, ID generators) does not
// belong to any specific BC — it is a low-level utility package shared across
// multiple BCs — so it is never matched by the domainImportBC regex (which
// matches only the exact internal/domain/<seg>/ shape) in the first place.
// This repository currently has no "shared type" case that would cause a
// false positive here (every domain/<bc> file imports no domain/ package
// outside its own BC — this rule is a guard against future regressions).
func checkNoCrossBCDomainImport(root string) RuleResult {
	result := RuleResult{Section: "no-cross-bc-domain-import"}
	domainDir := filepath.Join(root, "internal", "domain")
	if _, err := os.Stat(domainDir); os.IsNotExist(err) {
		result.Findings = append(result.Findings, skipFinding("internal/domain/ not found"))
		return result
	}

	found := false
	walkErr := filepath.WalkDir(domainDir, func(path string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() || !strings.HasSuffix(path, ".go") || strings.HasSuffix(d.Name(), "_test.go") {
			return nil
		}
		slashPath := filepath.ToSlash(path)
		selfMatch := domainImportBC.FindStringSubmatch(slashPath[strings.Index(slashPath, "/internal/domain/"):])
		if selfMatch == nil {
			return nil
		}
		selfBC := selfMatch[1]
		rel, _ := filepath.Rel(root, path)

		imports, parseErr := fileImportPaths(path)
		if parseErr != nil {
			found = true
			result.Findings = append(result.Findings, failFinding(rel, "failed to parse Go file: "+parseErr.Error()))
			return nil
		}

		var otherBCs []string
		for _, imp := range imports {
			m := domainImportBC.FindStringSubmatch("/" + filepath.ToSlash(imp))
			if m != nil && m[1] != selfBC {
				otherBCs = append(otherBCs, m[1])
			}
		}
		found = true
		if len(otherBCs) > 0 {
			result.Findings = append(result.Findings, failFinding(rel,
				"directly imports another Bounded Context's domain package ("+strings.Join(otherBCs, ", ")+") — an Aggregate must reference another Aggregate only by its ID string (docs/architecture/tactical-ddd.md)"))
		} else {
			result.Findings = append(result.Findings, passFinding(rel))
		}
		return nil
	})
	if walkErr != nil {
		result.Findings = append(result.Findings, failFinding(domainDir, "directory walk failed: "+walkErr.Error()))
	} else if !found {
		result.Findings = append(result.Findings, skipFinding("no .go files under internal/domain/<bc>/"))
	}
	return result
}

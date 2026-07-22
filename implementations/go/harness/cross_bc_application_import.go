package main

import (
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
)

// domainImportBC — extracts the <bc> segment from internal/domain/<bc>/... in an import path.
var domainImportBC = regexp.MustCompile(`/internal/domain/([^/]+)(?:/|$)`)

// checkCrossBCRepositoryInApplication — [14] no-cross-bc-repository-in-application:
// cross-domain-communication.md — "do not directly inject another BC's
// Repository or Service into the Application layer. Access only through an
// Adapter."
//
// internal/application/{command,query,event}/ is a flat package not split
// into per-domain subpackages (see the payment_card_adapter.go comment in
// CLAUDE.md), so the file path alone cannot tell "which BC this file belongs
// to". Instead, this is judged by the set of internal/domain/<bc>/ packages
// the file actually imports — a well-formed Application file imports only the
// domain package of the single BC whose Aggregate it coordinates (any other
// BC is accessed only through that BC's own View type defined by its
// infrastructure/acl adapter, so no domain import is needed for it). If a
// single file imports domain packages from 2 or more different BCs, that is a
// signal that it directly references another BC's Repository/Aggregate
// without going through an Adapter.
func checkCrossBCRepositoryInApplication(root string) RuleResult {
	result := RuleResult{Section: "no-cross-bc-repository-in-application"}
	found := false
	walkErr := filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() || !strings.HasSuffix(path, ".go") {
			return nil
		}
		if strings.HasSuffix(d.Name(), "_test.go") {
			return nil
		}
		slashPath := filepath.ToSlash(path)
		if !strings.Contains(slashPath, "/internal/application/") {
			return nil
		}
		rel, _ := filepath.Rel(root, path)
		imports, parseErr := fileImportPaths(path)
		if parseErr != nil {
			found = true
			result.Findings = append(result.Findings, failFinding(rel, "failed to parse Go file: "+parseErr.Error()))
			return nil
		}

		bcSet := map[string]bool{}
		for _, imp := range imports {
			m := domainImportBC.FindStringSubmatch("/" + filepath.ToSlash(imp))
			if m != nil {
				bcSet[m[1]] = true
			}
		}
		if len(bcSet) == 0 {
			return nil // a file that imports no domain/ package at all (e.g. an adapter definition) is out of scope
		}
		found = true
		if len(bcSet) > 1 {
			var bcs []string
			for bc := range bcSet {
				bcs = append(bcs, bc)
			}
			sort.Strings(bcs)
			result.Findings = append(result.Findings, failFinding(rel,
				"simultaneously imports domain packages from different Bounded Contexts ("+strings.Join(bcs, ", ")+") — another BC must be accessed only through an infrastructure/acl Adapter (docs/architecture/cross-domain-communication.md)"))
		} else {
			result.Findings = append(result.Findings, passFinding(rel))
		}
		return nil
	})
	if walkErr != nil {
		result.Findings = append(result.Findings, failFinding(root, "directory walk failed: "+walkErr.Error()))
	} else if !found {
		result.Findings = append(result.Findings, skipFinding("no domain/ imports inside internal/application/"))
	}
	return result
}

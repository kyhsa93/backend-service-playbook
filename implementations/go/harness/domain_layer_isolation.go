package main

import (
	"os"
	"path/filepath"
	"strings"
)

// forbiddenDomainImportSegments — the higher-layer path segments that the
// domain/ layer must not import (layer-architecture.md: "the Domain layer
// depends on no layer"). Because this is path-based rather than a blocklist
// enumerating specific library names, any new package created under
// application/infrastructure/interface (even as more domains are added) is
// automatically covered.
var forbiddenDomainImportSegments = []string{
	"/internal/application/",
	"/internal/infrastructure/",
	"/internal/interface/",
}

// checkDomainLayerIsolation — [10] domain-layer-isolation: internal/domain/**/*.go
// must not import any package under
// internal/application|infrastructure|interface/ (layer-architecture.md).
// Because import declarations are precisely parsed with go/parser
// (import_paths.go), mentioning those paths inside a comment or string
// literal never causes a false positive.
func checkDomainLayerIsolation(root string) RuleResult {
	result := RuleResult{Section: "domain-layer-isolation"}
	found := false
	walkErr := filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() || !strings.HasSuffix(path, ".go") {
			return nil
		}
		if strings.HasSuffix(d.Name(), "_test.go") {
			return nil
		}
		slashPath := filepath.ToSlash(path)
		if !strings.Contains(slashPath, "/internal/domain/") {
			return nil
		}
		rel, _ := filepath.Rel(root, path)
		imports, parseErr := fileImportPaths(path)
		if parseErr != nil {
			found = true
			result.Findings = append(result.Findings, failFinding(rel, "failed to parse Go file: "+parseErr.Error()))
			return nil
		}
		found = true
		var violated []string
		for _, imp := range imports {
			slashImp := "/" + filepath.ToSlash(imp) + "/"
			for _, seg := range forbiddenDomainImportSegments {
				if strings.Contains(slashImp, seg) {
					violated = append(violated, imp)
					break
				}
			}
		}
		if len(violated) > 0 {
			result.Findings = append(result.Findings, failFinding(rel,
				"the domain/ layer imports a higher layer ("+strings.Join(violated, ", ")+") — Domain must be framework-independent code that depends on no layer (layer-architecture.md)"))
		} else {
			result.Findings = append(result.Findings, passFinding(rel))
		}
		return nil
	})
	if walkErr != nil {
		result.Findings = append(result.Findings, failFinding(root, "directory walk failed: "+walkErr.Error()))
	} else if !found {
		result.Findings = append(result.Findings, skipFinding("internal/domain/ not found"))
	}
	return result
}

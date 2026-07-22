package main

import (
	"os"
	"path/filepath"
	"strings"
)

// checkInterfaceNoInfrastructure — [11] interface-no-infrastructure:
// internal/interface/**/*.go (HTTP handlers/routers) must not directly import
// internal/infrastructure/ — it must depend only on internal/application/
// (Command/Query handlers), following the Interface -> Application -> Domain
// dependency direction described in layer-architecture.md. For technical
// concerns that need an infrastructure implementation (e.g. JWT verification),
// declare a small interface near the consumer (interface/http/middleware/ etc.)
// and receive it via structural typing (the same "the interface lives near
// the layer that uses it, the implementation lives in infrastructure"
// principle described in authentication.md — TokenIssuer/PasswordHasher
// already follow this pattern).
func checkInterfaceNoInfrastructure(root string) RuleResult {
	result := RuleResult{Section: "interface-no-infrastructure"}
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
			if strings.Contains("/"+filepath.ToSlash(imp)+"/", "/internal/infrastructure/") {
				violated = append(violated, imp)
			}
		}
		if len(violated) > 0 {
			result.Findings = append(result.Findings, failFinding(rel,
				"the interface/ layer directly imports infrastructure/ ("+strings.Join(violated, ", ")+") — an HTTP handler/router must depend only on application/ (Command/Query handlers) (layer-architecture.md)"))
		} else {
			result.Findings = append(result.Findings, passFinding(rel))
		}
		return nil
	})
	if walkErr != nil {
		result.Findings = append(result.Findings, failFinding(root, "directory walk failed: "+walkErr.Error()))
	} else if !found {
		result.Findings = append(result.Findings, skipFinding("internal/interface/ not found"))
	}
	return result
}

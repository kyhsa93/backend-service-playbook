package main

import (
	"os"
	"path/filepath"
	"strings"
)

// checkHandlerPlacement — [4] Handler file placement: CQRS handlers -> application/command|query/, HTTP handlers -> interface/http/
func checkHandlerPlacement(root string) RuleResult {
	result := RuleResult{Section: "handler-placement"}
	found := false
	walkErr := filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() || !strings.HasSuffix(path, ".go") {
			return nil
		}
		name := d.Name()
		rel, _ := filepath.Rel(root, path)
		pathSlash := filepath.ToSlash(path)

		if strings.HasSuffix(name, "_handler.go") &&
			!strings.HasSuffix(name, "_event_handler.go") {
			found = true
			// If the file is at the HTTP handler location (interface/http/)
			// documented by guide.md, exclude it from the CQRS handler
			// (application/command|query/) rule and pass it as-is.
			switch {
			case strings.Contains(pathSlash, "/interface/http/"):
				result.Findings = append(result.Findings, passFinding(rel+" (HTTP handler)"))
			case strings.Contains(pathSlash, "/application/command/"),
				strings.Contains(pathSlash, "/application/query/"):
				result.Findings = append(result.Findings, passFinding(rel))
			default:
				result.Findings = append(result.Findings, failFinding(rel, "a handler file must be under application/command/, application/query/, or interface/http/"))
			}
		}
		return nil
	})
	if walkErr != nil {
		result.Findings = append(result.Findings, failFinding(root, "directory walk failed: "+walkErr.Error()))
	} else if !found {
		result.Findings = append(result.Findings, skipFinding("no handler files"))
	}
	return result
}

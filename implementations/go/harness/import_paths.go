package main

import (
	"go/parser"
	"go/token"
	"strconv"
)

// fileImportPaths extracts and returns only the path strings (quotes stripped)
// listed in a Go source file's import declarations. Several rules
// (domain-layer-isolation, interface-no-infrastructure,
// no-cross-bc-repository-in-application, no-logging-in-domain) need to
// determine "does this file import a given layer/package" — approximating the
// import block with a regex, as repository_naming.go and others do, leaves
// room for false positives on path-like text inside comments or string
// literals (stripGoComments in outbox_drain_order.go exists precisely to work
// around that problem). Import declarations only require go/parser's
// parser.ImportsOnly mode to parse the package clause + imports (function
// bodies are not parsed, so it is safe against syntax errors and fast), so
// this uses the standard library parser directly instead of a regex — the
// result is both simpler and fully precise.
func fileImportPaths(path string) ([]string, error) {
	fset := token.NewFileSet()
	f, err := parser.ParseFile(fset, path, nil, parser.ImportsOnly)
	if err != nil {
		return nil, err
	}
	paths := make([]string, 0, len(f.Imports))
	for _, imp := range f.Imports {
		unquoted, err := strconv.Unquote(imp.Path.Value)
		if err != nil {
			continue
		}
		paths = append(paths, unquoted)
	}
	return paths, nil
}

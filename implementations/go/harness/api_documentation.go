package main

import (
	"go/ast"
	"go/parser"
	"go/token"
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

// checkAPIDocumentation — api-documentation: docs/architecture/api-response.md's
// "Machine-readable API documentation (OpenAPI)" section requires every REST
// handler to carry complete swag (github.com/swaggo/swag) annotations —
// generated from the same comments this rule reads, not maintained as a
// separate hand-written document. The completeness bar mirrors nestjs's own
// api-documentation.evaluator.ts (the reference implementation this repo's
// OpenAPI convention was first built against): an operationId/route alone is
// not sufficient — every handler needs a `@Summary` and a `@Description`,
// and needs at least one `@Failure` annotation documenting a non-2xx
// response, since documenting only the success response is the most common
// way API documentation silently rots.
//
// A "handler" is identified structurally, not by name: any function or
// method declared under internal/interface/**/*.go (excluding _test.go)
// whose signature is exactly func(http.ResponseWriter, *http.Request) — the
// same signature net/http's ServeMux requires, so this matches precisely the
// set of functions router.go can register as a route, with no false
// positives on unrelated helpers like parsePagination/writeJSON that have a
// different signature.
//
// The one narrow exemption is /health/* routes (matched via the handler's own
// @Router annotation, not by file/function name) — an orchestrator liveness
// probe has no failure path to document by construction (it always returns
// 200, see internal/interface/http/health_handler.go's Live), the same
// reasoning rate-limiting.md already uses to exclude health checks from rate
// limiting ("not a business endpoint"). @Summary/@Description are still
// required even for a health route — only the @Failure requirement is
// relaxed.
const apiDocDoc = "docs/architecture/api-response.md#machine-readable-api-documentation-openapi"

// These match only an annotation token at the start of a comment line (optional
// leading whitespace), not the substring anywhere in the text — a plain-English
// doc comment mentioning "the @Summary annotation" in prose must not itself
// count as satisfying the requirement.
var (
	summaryAnnotation     = regexp.MustCompile(`(?m)^\s*@Summary\b`)
	descriptionAnnotation = regexp.MustCompile(`(?m)^\s*@Description\b`)
	failureAnnotation     = regexp.MustCompile(`(?m)^\s*@Failure\b`)
	routerAnnotation      = regexp.MustCompile(`(?m)^\s*@Router\s+(\S+)`)
)

func checkAPIDocumentation(root string) RuleResult {
	result := RuleResult{Section: "api-documentation"}
	found := false

	walkErr := filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() || !strings.HasSuffix(path, ".go") {
			return nil
		}
		if strings.HasSuffix(d.Name(), "_test.go") {
			return nil
		}
		if !strings.Contains(filepath.ToSlash(path), "/internal/interface/") {
			return nil
		}

		fset := token.NewFileSet()
		file, parseErr := parser.ParseFile(fset, path, nil, parser.ParseComments)
		if parseErr != nil {
			return nil // a syntax error is caught by go build/go vet elsewhere, not this rule's concern
		}
		rel, _ := filepath.Rel(root, path)

		for _, decl := range file.Decls {
			fn, ok := decl.(*ast.FuncDecl)
			if !ok || !isHTTPHandlerSignature(fn) {
				continue
			}
			found = true
			label := rel + " (" + fn.Name.Name + ")"

			docText := ""
			if fn.Doc != nil {
				docText = fn.Doc.Text()
			}

			hasSummary := summaryAnnotation.MatchString(docText)
			hasDescription := descriptionAnnotation.MatchString(docText)
			if !hasSummary || !hasDescription {
				result.Findings = append(result.Findings, failFinding(label,
					"missing a swag @Summary/@Description comment above the handler — an operationId/route alone is not sufficient ("+apiDocDoc+")"))
				continue
			}

			isHealthRoute := false
			if m := routerAnnotation.FindStringSubmatch(docText); m != nil {
				isHealthRoute = strings.HasPrefix(m[1], "/health/")
			}
			hasFailure := failureAnnotation.MatchString(docText)
			if !hasFailure && !isHealthRoute {
				result.Findings = append(result.Findings, failFinding(label,
					"documents only the success response — no @Failure annotation documents a non-2xx response ("+apiDocDoc+")"))
				continue
			}

			result.Findings = append(result.Findings, passFinding(label))
		}
		return nil
	})
	if walkErr != nil {
		result.Findings = append(result.Findings, failFinding(root, "directory walk failed: "+walkErr.Error()))
	} else if !found {
		result.Findings = append(result.Findings, skipFinding("no internal/interface/**/*.go handler with a func(http.ResponseWriter, *http.Request) signature"))
	}
	return result
}

// isHTTPHandlerSignature reports whether fn has exactly the two parameters
// net/http's HandlerFunc requires: (http.ResponseWriter, *http.Request). This
// matches regardless of the parameter names or of fn being a plain function
// vs. a method with a receiver.
func isHTTPHandlerSignature(fn *ast.FuncDecl) bool {
	params := fn.Type.Params
	if params == nil {
		return false
	}
	var types []ast.Expr
	for _, field := range params.List {
		n := len(field.Names)
		if n == 0 {
			n = 1
		}
		for i := 0; i < n; i++ {
			types = append(types, field.Type)
		}
	}
	if len(types) != 2 {
		return false
	}
	return isSelectorTo(types[0], "http", "ResponseWriter") && isPointerToSelector(types[1], "http", "Request")
}

func isSelectorTo(expr ast.Expr, pkg, name string) bool {
	sel, ok := expr.(*ast.SelectorExpr)
	if !ok {
		return false
	}
	ident, ok := sel.X.(*ast.Ident)
	return ok && ident.Name == pkg && sel.Sel.Name == name
}

func isPointerToSelector(expr ast.Expr, pkg, name string) bool {
	star, ok := expr.(*ast.StarExpr)
	if !ok {
		return false
	}
	return isSelectorTo(star.X, pkg, name)
}

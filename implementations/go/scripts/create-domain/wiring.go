package main

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// Go has no DI container like NestJS's @Module({ providers: [...] }) —
// constructor chaining in main.go plays that role instead (module-pattern.md).
// The Card BC also has no dedicated Relay/Consumer — it registers its events in
// the single shared map[string]outbox.Handler that main.go assembles, and
// outbox.Poller (publishing) / outbox.Consumer (receiving/executing) share that
// map (internal/infrastructure/outbox/poller.go, consumer.go). So unlike nestjs,
// where this generator only has to touch a single app-module.ts, it edits
// main.go (handler map + repository assembly) and router.go (HTTP routing +
// Handler assembly) together, anchored on actual strings already present in
// each file. The Command Handler never references this handlers map at all
// (synchronous draining is prohibited, see domain-events.md) — it returns
// immediately after saving.

// findMatchingClose finds the index of the closing character (close) that
// matches the opening character (open) at src[openIdx]. It's a simple depth
// count that doesn't distinguish string literals/comments, but that's enough
// for code like this repo's main.go/router.go where string literals never
// contain braces/parens.
func findMatchingClose(src string, openIdx int, open, closeCh byte) (int, error) {
	if openIdx < 0 || openIdx >= len(src) || src[openIdx] != open {
		return -1, fmt.Errorf("openIdx does not point at the opening character (%q)", string(open))
	}
	depth := 0
	for i := openIdx; i < len(src); i++ {
		switch src[i] {
		case open:
			depth++
		case closeCh:
			depth--
			if depth == 0 {
				return i, nil
			}
		}
	}
	return -1, fmt.Errorf("no matching closing character (%q) found", string(closeCh))
}

// insertAfterLine inserts insertion right after the line containing the anchor string.
func insertAfterLine(src, anchor, insertion string) (string, error) {
	idx := strings.Index(src, anchor)
	if idx < 0 {
		return "", fmt.Errorf("anchor not found: %q", anchor)
	}
	nl := strings.Index(src[idx:], "\n")
	if nl < 0 {
		return "", fmt.Errorf("no newline found on the anchor's line: %q", anchor)
	}
	pos := idx + nl + 1
	return src[:pos] + insertion + src[pos:], nil
}

// insertBeforeMarker inserts insertion right before marker.
func insertBeforeMarker(src, marker, insertion string) (string, error) {
	idx := strings.Index(src, marker)
	if idx < 0 {
		return "", fmt.Errorf("marker not found: %q", marker)
	}
	return src[:idx] + insertion + src[idx:], nil
}

// insertBeforeMatchingClose finds openMarker (a string ending in the opening
// character open), then inserts insertion right before the closing character
// that matches that opening character. It uses bracket-depth counting rather
// than direct string matching so the insertion point stays stable across
// repeated runs (adding multiple domains) to things like map literals or a
// function call's argument list.
func insertBeforeMatchingClose(src, openMarker string, open, closeCh byte, insertion string) (string, error) {
	markerIdx := strings.Index(src, openMarker)
	if markerIdx < 0 {
		return "", fmt.Errorf("openMarker not found: %q", openMarker)
	}
	openIdx := markerIdx + len(openMarker) - 1
	closeIdx, err := findMatchingClose(src, openIdx, open, closeCh)
	if err != nil {
		return "", fmt.Errorf("matching failed after %q: %w", openMarker, err)
	}
	return src[:closeIdx] + insertion + src[closeIdx:], nil
}

// WireResult reports which files the wiring step actually changed, and whether
// it was skipped because wiring was already applied.
type WireResult struct {
	ChangedFiles []string
	AlreadyWired bool
}

// dependencyAssemblySites finds every file under targetRoot that contains
// dependency-assembly code "shaped like" main.go. cmd/server/main.go isn't the
// only target — e2e tests like this repo's test/account_e2e_test.go
// re-assemble the repository/Relay/httphandler.NewRouter(...) themselves
// without going through main.go, and missing such a file leaves a
// "not enough arguments in call to NewRouter" compile error that only shows up
// under go vet (which also type-checks test files) — a bug actually found while
// building this generator. Any .go file containing both anchors (the Card
// repository assembly line and the httphandler.NewRouter call) is treated as an
// assembly site.
func dependencyAssemblySites(targetRoot string) ([]string, error) {
	var sites []string
	err := filepath.Walk(targetRoot, func(path string, info os.FileInfo, err error) error {
		if err != nil || info.IsDir() || !strings.HasSuffix(path, ".go") {
			return nil
		}
		data, readErr := os.ReadFile(path)
		if readErr != nil {
			return nil
		}
		src := string(data)
		if strings.Contains(src, "persistence.NewCardRepository(db)") && strings.Contains(src, "httphandler.NewRouter(") {
			sites = append(sites, path)
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	return sites, nil
}

// WireMainAndRouter programmatically inserts the new domain's
// repository/handler/route into router.go and every dependency assembly site
// (main.go + e2e tests that reassemble it, etc). If already registered, it does
// nothing and returns AlreadyWired=true.
func WireMainAndRouter(targetRoot string, n Names) (WireResult, error) {
	mainPath := filepath.Join(targetRoot, "cmd", "server", "main.go")
	routerPath := filepath.Join(targetRoot, "internal", "interface", "http", "router.go")

	mainSrc, err := os.ReadFile(mainPath)
	if err != nil {
		return WireResult{}, fmt.Errorf("failed to read main.go: %w", err)
	}

	repoCtorName := fmt.Sprintf("persistence.New%sRepository", n.Domain)
	if strings.Contains(string(mainSrc), repoCtorName) {
		return WireResult{AlreadyWired: true}, nil
	}

	sites, err := dependencyAssemblySites(targetRoot)
	if err != nil {
		return WireResult{}, fmt.Errorf("failed to locate dependency assembly site: %w", err)
	}
	if len(sites) == 0 {
		return WireResult{}, fmt.Errorf("no dependency assembly site found (no .go file contains both persistence.NewCardRepository(db) and httphandler.NewRouter()")
	}

	var changed []string
	for _, path := range sites {
		src, readErr := os.ReadFile(path)
		if readErr != nil {
			return WireResult{}, fmt.Errorf("failed to read %s: %w", path, readErr)
		}
		newSrc, wireErr := wireDependencyAssembly(string(src), n)
		if wireErr != nil {
			return WireResult{}, fmt.Errorf("wiring failed for %s: %w", path, wireErr)
		}
		if err := os.WriteFile(path, []byte(newSrc), 0o644); err != nil {
			return WireResult{}, fmt.Errorf("failed to write %s: %w", path, err)
		}
		changed = append(changed, path)
	}

	routerSrc, err := os.ReadFile(routerPath)
	if err != nil {
		return WireResult{}, fmt.Errorf("failed to read router.go: %w", err)
	}
	newRouter, err := wireRouter(string(routerSrc), n)
	if err != nil {
		return WireResult{}, fmt.Errorf("wiring failed for router.go: %w", err)
	}
	if err := os.WriteFile(routerPath, []byte(newRouter), 0o644); err != nil {
		return WireResult{}, fmt.Errorf("failed to write router.go: %w", err)
	}
	changed = append(changed, routerPath)

	return WireResult{ChangedFiles: changed}, nil
}

// wireDependencyAssembly wires the new domain into a single file shaped like
// main.go (Card repository assembly -> shared map[string]outbox.Handler ->
// httphandler.NewRouter(...) call).
func wireDependencyAssembly(src string, n Names) (string, error) {
	// 1. Repository assembly — add right after the cardRepo construction line
	// (Card is always present, a stable anchor).
	repoLine := fmt.Sprintf("\t%sRepo := persistence.New%sRepository(db)\n", n.DomainCamel, n.Domain)
	src, err := insertAfterLine(src, "cardRepo := persistence.NewCardRepository(db)\n", repoLine)
	if err != nil {
		return "", err
	}

	// 2. Register the event handler in the shared handlers map (shared by
	// outbox.Poller/outbox.Consumer) — insert right before the map literal's
	// closing brace (always appended last, regardless of how many domains are
	// already registered).
	entry := fmt.Sprintf("\t\t\"%sCancelled\": event.New%sCancelledEventHandler().Handle,\n", n.Domain, n.Domain)
	src, err = insertBeforeMatchingClose(src, "map[string]outbox.Handler{", '{', '}', entry)
	if err != nil {
		return "", err
	}

	// 3. Add the new repository to the end of the NewRouter call's argument list.
	arg := fmt.Sprintf(", %sRepo", n.DomainCamel)
	src, err = insertBeforeMatchingClose(src, "httphandler.NewRouter(", '(', ')', arg)
	if err != nil {
		return "", err
	}

	return src, nil
}

func wireRouter(src string, n Names) (string, error) {
	// 1. Add the new domain package import — right after the domain/card import
	// (Card is always present).
	importLine := fmt.Sprintf("\t\"%s/internal/domain/%s\"\n", n.ModulePath, n.DomainLower)
	cardImportAnchor := fmt.Sprintf("\"%s/internal/domain/card\"\n", n.ModulePath)
	src, err := insertAfterLine(src, cardImportAnchor, importLine)
	if err != nil {
		return "", err
	}

	// 2. Add the new repository parameter to the NewRouter function signature —
	// the point where the parameter list ends (right before the return type
	// starts) stays the same string across repeated runs.
	param := fmt.Sprintf(", %sRepo %s.Repository", n.DomainCamel, n.DomainLower)
	src, err = insertBeforeMarker(src, ") (http.Handler, *HealthHandler) {", param)
	if err != nil {
		return "", err
	}

	// 3. Assemble the Command/Query Handlers + HTTP Handler — add right after
	// the cardHTTP assembly.
	construction := fmt.Sprintf(
		"\n\t// %s BC — generated by the scaffolding generator (scripts/create-domain).\n"+
			"\tcreate%sHandler := command.NewCreate%sHandler(%sRepo)\n"+
			"\tcancel%sHandler := command.NewCancel%sHandler(%sRepo)\n"+
			"\tget%sHandler := query.NewGet%sHandler(%sRepo)\n"+
			"\t%sHTTP := New%sHandler(create%sHandler, cancel%sHandler, get%sHandler)\n",
		n.Domain,
		n.Domain, n.Domain, n.DomainCamel,
		n.Domain, n.Domain, n.DomainCamel,
		n.Domain, n.Domain, n.DomainCamel,
		n.DomainCamel, n.Domain, n.Domain, n.Domain, n.Domain,
	)
	src, err = insertAfterLine(src, "cardHTTP := NewCardHandler(issueCardHandler, getCardHandler)\n", construction)
	if err != nil {
		return "", err
	}

	// 4. Register routes on the protected mux — add right after Card's GetCard route.
	routes := fmt.Sprintf(
		"\tprotected.HandleFunc(\"POST /%s\", %sHTTP.Create%s)\n"+
			"\tprotected.HandleFunc(\"POST /%s/{id}/cancel\", %sHTTP.Cancel%s)\n"+
			"\tprotected.HandleFunc(\"GET /%s/{id}\", %sHTTP.Get%s)\n",
		n.DomainsLower, n.DomainCamel, n.Domain,
		n.DomainsLower, n.DomainCamel, n.Domain,
		n.DomainsLower, n.DomainCamel, n.Domain,
	)
	src, err = insertAfterLine(src, `protected.HandleFunc("GET /cards/{cardId}", cardHTTP.GetCard)`+"\n", routes)
	if err != nil {
		return "", err
	}

	// 5. Also register the new path on the rate-limited target (limited mux) — add right after Card's registration.
	limited := fmt.Sprintf(
		"\tlimited.Handle(\"/%s\", middleware.RequireAuth(jwtService)(protected))\n"+
			"\tlimited.Handle(\"/%s/\", middleware.RequireAuth(jwtService)(protected))\n",
		n.DomainsLower, n.DomainsLower,
	)
	src, err = insertAfterLine(src, `limited.Handle("/cards/", middleware.RequireAuth(jwtService)(protected))`+"\n", limited)
	if err != nil {
		return "", err
	}

	return src, nil
}

// PrintWiringSnippet shows what to apply manually when run without --wire.
func PrintWiringSnippet(n Names) {
	fmt.Println()
	fmt.Println("--- Add manually to main.go / router.go (not auto-applied since --wire wasn't given) ---")
	fmt.Println()
	fmt.Printf("cmd/server/main.go:\n")
	fmt.Printf("  %sRepo := persistence.New%sRepository(db)\n", n.DomainCamel, n.Domain)
	fmt.Printf("  add to the shared outboxHandlers map (map[string]outbox.Handler): \"%sCancelled\": event.New%sCancelledEventHandler().Handle,\n", n.Domain, n.Domain)
	fmt.Printf("  httphandler.NewRouter(... , %sRepo)\n", n.DomainCamel)
	fmt.Println()
	fmt.Printf("internal/interface/http/router.go:\n")
	fmt.Printf("  add to NewRouter parameters: %sRepo %s.Repository\n", n.DomainCamel, n.DomainLower)
	fmt.Printf("  assemble create/cancel/get %s handlers + %sHTTP := New%sHandler(...)\n", n.Domain, n.DomainCamel, n.Domain)
	fmt.Printf("  register the /%s route on the protected/limited mux\n", n.DomainsLower)
	fmt.Println()
}

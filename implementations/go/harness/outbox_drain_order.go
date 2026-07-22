package main

import (
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

// checkOutboxDrainOrder — [8] verifies the synchronous-drain-forbidden rule —
// a core invariant from domain-events.md
//
// Outbox → SQS publishing is handled by an independently, periodically running
// outbox.Poller, and SQS → Handler execution by outbox.Consumer (both
// goroutines in main.go). A Command Handler must save and return immediately —
// it must not reference OutboxRelay/OutboxPoller/OutboxConsumer or call a
// drain method (ProcessPending/Poll/drainOnce). Without this check, no one
// would catch it if someone added a drain call to a Command Handler.
var (
	// Forbids all references to
	// OutboxRelay/OutboxPoller/OutboxConsumer/outbox.Poller/outbox.Consumer/outbox.Relay
	// — catches synchronous draining reintroduced under any of these names.
	forbiddenSymbol = regexp.MustCompile(
		`\bOutboxRelay\b|\bOutboxPoller\b|\bOutboxConsumer\b|\boutbox\.Relay\b|\boutbox\.Poller\b|\boutbox\.Consumer\b`,
	)
	// Calls to drain methods such as .ProcessPending(/.Poll(/.drainOnce(.
	forbiddenCall = regexp.MustCompile(`\.\s*(?:ProcessPending|Poll|drainOnce)\s*\(`)
	saveCall      = regexp.MustCompile(`\.Save\w*\(`)
	blockComment  = regexp.MustCompile(`(?s)/\*.*?\*/`)
	lineComment   = regexp.MustCompile(`//[^\n]*`)
)

// stripGoComments returns the source with line/block comments removed. The
// checks in this file rely on text search (strings.Contains, regexes) rather
// than an AST, so merely mentioning an identifier like "OutboxRelay" inside a
// design-intent comment must not be mistaken for an actual dependency (e.g. a
// comment stating "this Handler does not directly reference
// outbox.Poller/outbox.Consumer", which in fact does not depend on that
// type). "//"/"/*" inside string literals are not common enough in this
// repository's actual source to cause this kind of false positive, so this
// approximation is sufficient in place of a full parser.
func stripGoComments(src string) string {
	src = blockComment.ReplaceAllString(src, "")
	src = lineComment.ReplaceAllString(src, "")
	return src
}

func checkOutboxDrainOrder(root string) RuleResult {
	result := RuleResult{Section: "outbox-drain-order"}
	found := false
	walkErr := filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() || !strings.HasSuffix(path, ".go") {
			return nil
		}
		name := d.Name()
		if strings.HasSuffix(name, "_test.go") ||
			!strings.HasSuffix(name, "_handler.go") ||
			strings.HasSuffix(name, "_event_handler.go") {
			return nil
		}
		content, readErr := os.ReadFile(path)
		if readErr != nil {
			return nil
		}
		src := stripGoComments(string(content))
		rel, _ := filepath.Rel(root, path)

		if forbiddenSymbol.MatchString(src) {
			found = true
			result.Findings = append(result.Findings, failFinding(rel,
				"references OutboxRelay/OutboxPoller/OutboxConsumer — a Command Handler must return immediately after saving, and Outbox → SQS publish/receive is solely the responsibility of the independently periodic outbox.Poller/outbox.Consumer (synchronous draining is forbidden, domain-events.md)"))
			return nil
		}
		if forbiddenCall.MatchString(src) {
			found = true
			result.Findings = append(result.Findings, failFinding(rel,
				"calls a drain method such as ProcessPending()/Poll()/drainOnce() — a Command Handler must return immediately after saving (synchronous draining is forbidden, domain-events.md)"))
			return nil
		}
		// If a Handler has a Save(...) call (i.e. it mutates state) and has none
		// of the forbidden symbols/calls, treat it as Pass for "does not drain
		// incorrectly" — files with no Save, such as Query Handlers, are out of
		// scope for this rule and are silently skipped.
		if saveCall.MatchString(src) {
			found = true
			result.Findings = append(result.Findings, passFinding(rel+" (confirmed no synchronous drain call after Save)"))
		}
		return nil
	})
	if walkErr != nil {
		result.Findings = append(result.Findings, failFinding(root, "directory walk failed: "+walkErr.Error()))
	} else if !found {
		result.Findings = append(result.Findings, skipFinding("no Command Handler calls Save(...)"))
	}
	return result
}

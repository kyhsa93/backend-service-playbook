package main

import (
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

// checkSharedInfra — [6] shared-infra: outbox / task-queue patterns
//
// If outbox/task-queue-related code exists, it must be grouped into a
// dedicated directory (named exactly "outbox"/"task-queue"), and that
// directory must actually implement the pattern's core types.
// shared-modules.md does not pin this directory's location to directly under
// internal/ — it may live under any concern-specific subpackage (e.g.
// internal/infrastructure/outbox/), so this recursively walks all of
// internal/ to check.
//
// Whether "the outbox pattern is in use" is determined by whether there is an
// actual reference to outbox.Writer (internal/infrastructure/persistence/
// etc.) — the transactional-save point that domain-events.md mandates for
// Repository implementations (and Application EventHandlers) — rather than
// being tripped up by an unrelated file whose name happens to contain the
// string "outbox" (e.g. migrations/000X_add_outbox.sql). Before the 2026-07
// async migration, the gate was based on a reference to OutboxRelay in the
// command package, but since Command Handlers no longer reference any
// outbox-related type at all under the synchronous-drain-forbidden rule, that
// signal disappeared — the gate has instead moved to the Repository ↔
// outbox.Writer connection, which always remains present.
func checkSharedInfra(root string) RuleResult {
	result := RuleResult{Section: "shared-infra"}
	result.Findings = append(result.Findings, checkOutboxPattern(root)...)
	result.Findings = append(result.Findings, checkTaskQueuePattern(root)...)
	return result
}

func checkOutboxPattern(root string) []Finding {
	usesOutboxWriter := false
	var outboxDirs []string
	walkErr := filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
		if err != nil {
			return nil
		}
		if d.IsDir() {
			if d.Name() == "outbox" {
				outboxDirs = append(outboxDirs, path)
			}
			return nil
		}
		if !strings.HasSuffix(path, ".go") || strings.HasSuffix(path, "_test.go") {
			return nil
		}
		content, readErr := os.ReadFile(path)
		if readErr != nil {
			return nil
		}
		if strings.Contains(string(content), "outbox.Writer") || strings.Contains(string(content), "outbox.NewWriter") {
			usesOutboxWriter = true
		}
		return nil
	})
	if walkErr != nil {
		return []Finding{failFinding(root, "directory walk failed: "+walkErr.Error())}
	}

	if !usesOutboxWriter {
		return []Finding{skipFinding("no outbox pattern")}
	}

	if len(outboxDirs) == 0 {
		return []Finding{failFinding("internal/**/outbox/", "references outbox.Writer but there is no dedicated outbox/ directory")}
	}

	// Relay (synchronous drain) is not used — Poller (Outbox → SQS publishing)
	// and Consumer (SQS → Handler execution) split that role between them
	// (domain-events.md).
	hasWriter, hasPoller, hasConsumer := false, false, false
	for _, dir := range outboxDirs {
		walkErr := filepath.WalkDir(dir, func(path string, d os.DirEntry, err error) error {
			if err != nil || d.IsDir() || !strings.HasSuffix(path, ".go") {
				return nil
			}
			content, readErr := os.ReadFile(path)
			if readErr != nil {
				return nil
			}
			src := string(content)
			if regexp.MustCompile(`(?m)^type\s+Writer\s+struct\b`).MatchString(src) {
				hasWriter = true
			}
			if regexp.MustCompile(`(?m)^type\s+Poller\s+struct\b`).MatchString(src) {
				hasPoller = true
			}
			if regexp.MustCompile(`(?m)^type\s+Consumer\s+struct\b`).MatchString(src) {
				hasConsumer = true
			}
			return nil
		})
		if walkErr != nil {
			return []Finding{failFinding(dir, "directory walk failed: "+walkErr.Error())}
		}
	}

	if hasWriter && hasPoller && hasConsumer {
		return []Finding{passFinding("internal/**/outbox/ (Writer/Poller/Consumer implementation confirmed)")}
	}
	var missing []string
	if !hasWriter {
		missing = append(missing, "Writer")
	}
	if !hasPoller {
		missing = append(missing, "Poller")
	}
	if !hasConsumer {
		missing = append(missing, "Consumer")
	}
	return []Finding{failFinding("internal/**/outbox/", "outbox/ directory exists, but could not find type declaration(s) for "+strings.Join(missing, ", "))}
}

func checkTaskQueuePattern(root string) []Finding {
	hasTaskFile := false
	hasTaskDir := false
	walkErr := filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
		if err != nil {
			return nil
		}
		name := d.Name()
		pathSlash := filepath.ToSlash(path)
		if d.IsDir() {
			if name == "task-queue" {
				hasTaskDir = true
			}
			return nil
		}
		if strings.Contains(name, "task_queue") && !strings.Contains(pathSlash, "/task-queue/") {
			hasTaskFile = true
		}
		return nil
	})
	if walkErr != nil {
		return []Finding{failFinding(root, "directory walk failed: "+walkErr.Error())}
	}

	if !hasTaskFile {
		return []Finding{skipFinding("no task-queue pattern")}
	}
	if hasTaskDir {
		return []Finding{passFinding("internal/**/task-queue/")}
	}
	return []Finding{failFinding("internal/**/task-queue/", "task files exist, but there is no dedicated task-queue/ directory")}
}

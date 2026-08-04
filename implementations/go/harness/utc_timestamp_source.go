package main

import (
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
)

// bareTimeNow matches a time.Now() call and, optionally, an immediately
// chained .UTC() conversion. Submatch 1 is non-empty only when the conversion
// is present, so a bare reading is detected as "match with an empty group 1".
var bareTimeNow = regexp.MustCompile(`\btime\.Now\(\)(\s*\.\s*UTC\(\))?`)

// checkUTCTimestampSource — utc-timestamp-source: a timestamp that is written
// to the database or embedded in a domain event must be read in UTC
// (docs/conventions.md, "the timezone rule").
//
// time.Now() returns a time.Time carrying the host's local location, and
// database/sql drivers (lib/pq among them) format that value using its own
// location before sending it. A TIMESTAMP (without time zone) column stores
// the wall-clock digits it is handed and records no offset, so identical code
// writes UTC on a UTC CI runner and local time on a developer machine — the
// column ends up holding values that cannot be compared with one another, and
// period arithmetic derived from them shifts by the offset.
//
// Scope — the rule reports only on internal/domain/** and
// internal/infrastructure/persistence/**. Those are the two places where a
// clock reading is, by construction, a value that gets persisted or shipped
// inside a domain event: the domain layer holds no timers or deadlines, and a
// repository exists to write rows. Reading the clock to measure elapsed time
// (durations, tickers, retry backoff, HTTP request latency) is
// location-independent and perfectly legitimate, and it lives in the
// interface/ middleware and infrastructure/ transport code that this scope
// deliberately leaves alone — which is what keeps the rule from flagging it.
func checkUTCTimestampSource(root string) RuleResult {
	result := RuleResult{Section: "utc-timestamp-source"}
	found := false
	walkErr := filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() || !strings.HasSuffix(path, ".go") {
			return nil
		}
		if strings.HasSuffix(d.Name(), "_test.go") {
			return nil
		}
		slashPath := filepath.ToSlash(path)
		inDomain := strings.Contains(slashPath, "/internal/domain/")
		inPersistence := strings.Contains(slashPath, "/internal/infrastructure/persistence/")
		if !inDomain && !inPersistence {
			return nil
		}
		content, readErr := os.ReadFile(path)
		if readErr != nil {
			return nil
		}
		src := stripGoComments(string(content))
		rel, _ := filepath.Rel(root, path)
		found = true

		bare := 0
		for _, m := range bareTimeNow.FindAllStringSubmatch(src, -1) {
			if m[1] == "" {
				bare++
			}
		}
		if bare > 0 {
			result.Findings = append(result.Findings, failFinding(rel,
				"reads the clock with a bare time.Now() ("+strconv.Itoa(bare)+
					" call(s)) — time.Now() carries the host's local location, and the driver formats it with that location "+
					"before writing it to a TIMESTAMP (without time zone) column, so the stored value depends on the "+
					"machine the process runs on. Route every persisted / domain-event / period-arithmetic timestamp "+
					"through the project's shared UTC clock helper (internal/common's Now(), which returns "+
					"time.Now().UTC()). Elapsed-time measurement stays on time.Now() and lives outside this rule's "+
					"scope, internal/domain/ and internal/infrastructure/persistence/ (docs/conventions.md)"))
		} else {
			result.Findings = append(result.Findings, passFinding(rel))
		}
		return nil
	})
	if walkErr != nil {
		result.Findings = append(result.Findings, failFinding(root, "directory walk failed: "+walkErr.Error()))
	} else if !found {
		result.Findings = append(result.Findings, skipFinding("no .go files in internal/domain/, internal/infrastructure/persistence/"))
	}
	return result
}

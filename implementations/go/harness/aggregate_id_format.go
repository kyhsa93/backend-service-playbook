package main

import (
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

// checkAggregateIDFormat — aggregate-id-format: docs/architecture/aggregate-id.md
// mandates that an Aggregate ID be "a 32-character hex string with the hyphens
// stripped from a UUID v4" — the document explicitly gives an example showing
// that using the raw hyphenated UUID string
// (`550e8400-e29b-41d4-a716-446655440000`) as-is is wrong. This checks whether
// this repository's actual ID-issuing utility (`common.NewID()` in
// `internal/common/id.go`) actually strips the hyphens — it is a single-file
// check, and does not cover whether a new domain's Aggregate constructor
// (developed independently by this repository) calls common.NewID() (as with
// other rules such as repository-naming, this repository enforces the premise
// that "an Aggregate ID always goes through this one utility, no matter
// where" only through the architecture documents, not mechanically — if
// multiple new utilities were created, this rule only looks at the first one
// it finds).
//
// Once NewID is found, its function body is judged as follows:
//   - PASS if there is a hyphen-stripping signal (things like
//     strings.ReplaceAll(..., "-", ...)).
//   - FAIL if it calls the uuid package with no hyphen-stripping signal at all
//     (i.e. it returns uuid.NewString()/uuid.New().String() unprocessed) —
//     it emits the hyphenated UUID as-is.
//   - PASS if it neither uses the uuid package nor has a hyphen-stripping
//     signal (e.g. a different generation approach such as crypto/rand +
//     hex.EncodeToString, which cannot produce hyphens in the first place) —
//     the fact that hex.EncodeToString cannot produce hyphens is itself the
//     safety signal.
//   - Otherwise (what it does cannot be determined by text search alone),
//     treat it as FAIL so that a missed violation is never let through
//     (a conservative default).
var (
	newIDFuncDecl  = regexp.MustCompile(`func\s+NewID\s*\([^)]*\)\s*string\s*\{`)
	uuidCallSignal = regexp.MustCompile(`\buuid\.\w+\(`)
	hyphenStripHex = regexp.MustCompile(`ReplaceAll\([^,]*,\s*"-"|Replace\([^,]*,\s*"-"|NewReplacer\(\s*"-"`)
	hexEncodeSafe  = regexp.MustCompile(`\bhex\.EncodeToString\(`)
)

func checkAggregateIDFormat(root string) RuleResult {
	result := RuleResult{Section: "aggregate-id-format"}

	var candidate string
	walkErr := filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() || !strings.HasSuffix(path, ".go") {
			return nil
		}
		if strings.HasSuffix(d.Name(), "_test.go") {
			return nil
		}
		if candidate != "" {
			return nil
		}
		content, readErr := os.ReadFile(path)
		if readErr != nil {
			return nil
		}
		src := stripGoComments(string(content))
		if newIDFuncDecl.MatchString(src) {
			candidate = path
		}
		return nil
	})
	if walkErr != nil {
		result.Findings = append(result.Findings, failFinding(root, "directory walk failed: "+walkErr.Error()))
		return result
	}
	if candidate == "" {
		result.Findings = append(result.Findings, skipFinding("could not find func NewID() string (expected location internal/common/id.go) — docs/architecture/aggregate-id.md"))
		return result
	}

	content, readErr := os.ReadFile(candidate)
	if readErr != nil {
		result.Findings = append(result.Findings, failFinding(candidate, "file read failed: "+readErr.Error()))
		return result
	}
	src := stripGoComments(string(content))
	rel, _ := filepath.Rel(root, candidate)

	loc := newIDFuncDecl.FindStringIndex(src)
	braceIdx := loc[1] - 1 // newIDFuncDecl itself ends with '{', so this is its position
	body := extractBalancedBlock(src, braceIdx, '{', '}')

	usesUUID := uuidCallSignal.MatchString(body)
	stripsHyphen := hyphenStripHex.MatchString(body)
	usesHexEncode := hexEncodeSafe.MatchString(body)

	switch {
	case stripsHyphen:
		result.Findings = append(result.Findings, passFinding(rel+" (NewID — hyphen stripping confirmed)"))
	case usesUUID:
		result.Findings = append(result.Findings, failFinding(rel,
			"NewID() calls the uuid package but has no hyphen-stripping code (e.g. strings.ReplaceAll) — "+
				"it must return a 32-character hex string with the hyphens stripped from a UUID v4 (docs/architecture/aggregate-id.md)"))
	case usesHexEncode:
		result.Findings = append(result.Findings, passFinding(rel+" (NewID — based on hex.EncodeToString, cannot produce hyphens)"))
	default:
		result.Findings = append(result.Findings, failFinding(rel,
			"cannot determine whether NewID() strips hyphens — "+
				"it must return a 32-character hex string with the hyphens stripped from a UUID v4 (docs/architecture/aggregate-id.md)"))
	}

	return result
}

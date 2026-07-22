package main

import (
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
)

var (
	fromLine        = regexp.MustCompile(`(?mi)^\s*FROM\s+\S+`)
	healthcheckLine = regexp.MustCompile(`(?mi)^\s*HEALTHCHECK\b`)
	userLine        = regexp.MustCompile(`(?mi)^\s*USER\s+\S+`)
)

// dockerignoreExcludes — the minimum set of excludes required by
// container.md/local-dev.md. The source repository (.git) and secrets
// (.env*) are the two things that must never end up in the image, so they
// serve as the minimum bar for "reasonable excludes".
var dockerignoreExcludes = []string{".git", ".env"}

// checkDockerfileConventions — [18] dockerfile-conventions: reads examples/Dockerfile
// and its sibling .dockerignore directly and checks them (container.md). Unlike
// the other rules, this one does not recursively walk the directory tree — it
// is a single-file check against exactly two files (<root>/Dockerfile,
// <root>/.dockerignore), not Go source.
//
// (a) multi-stage build — 2 or more FROM lines
// (b) a HEALTHCHECK instruction is present
// (c) .dockerignore exists and includes reasonable excludes like .git/.env*
func checkDockerfileConventions(root string) RuleResult {
	result := RuleResult{Section: "dockerfile-conventions"}
	dockerfilePath := filepath.Join(root, "Dockerfile")
	content, err := os.ReadFile(dockerfilePath)
	if os.IsNotExist(err) {
		result.Findings = append(result.Findings, skipFinding("Dockerfile not found"))
		return result
	}
	if err != nil {
		result.Findings = append(result.Findings, failFinding("Dockerfile", "read failed: "+err.Error()))
		return result
	}
	src := string(content)

	fromCount := len(fromLine.FindAllString(src, -1))
	if fromCount >= 2 {
		result.Findings = append(result.Findings, passFinding("Dockerfile (multi-stage, "+strconv.Itoa(fromCount)+" FROM lines)"))
	} else {
		result.Findings = append(result.Findings, failFinding("Dockerfile", "not a multi-stage build ("+strconv.Itoa(fromCount)+" FROM line(s)) — the build stage and the runtime stage must be separated (docs/architecture/container.md)"))
	}

	if healthcheckLine.MatchString(src) {
		result.Findings = append(result.Findings, passFinding("Dockerfile (HEALTHCHECK present)"))
	} else {
		result.Findings = append(result.Findings, failFinding("Dockerfile", "no HEALTHCHECK instruction (docs/architecture/container.md)"))
	}

	if userLine.MatchString(src) {
		result.Findings = append(result.Findings, passFinding("Dockerfile (non-root USER present)"))
	} else {
		result.Findings = append(result.Findings, failFinding("Dockerfile", "no USER instruction — the container runs as root (docs/architecture/container.md)"))
	}

	dockerignorePath := filepath.Join(root, ".dockerignore")
	diContent, diErr := os.ReadFile(dockerignorePath)
	switch {
	case os.IsNotExist(diErr):
		result.Findings = append(result.Findings, failFinding(".dockerignore", "no .dockerignore file — unnecessary files (.git, secrets, etc.) could end up in the build context (docs/architecture/container.md)"))
	case diErr != nil:
		result.Findings = append(result.Findings, failFinding(".dockerignore", "read failed: "+diErr.Error()))
	default:
		diSrc := string(diContent)
		var missing []string
		for _, exclude := range dockerignoreExcludes {
			if !strings.Contains(diSrc, exclude) {
				missing = append(missing, exclude)
			}
		}
		if len(missing) == 0 {
			result.Findings = append(result.Findings, passFinding(".dockerignore (.git/.env exclusion confirmed)"))
		} else {
			result.Findings = append(result.Findings, failFinding(".dockerignore", "missing reasonable excludes ("+strings.Join(missing, ", ")+")"))
		}
	}

	return result
}

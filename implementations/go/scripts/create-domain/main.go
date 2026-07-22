// New-domain scaffolding generator — turns the "Practical Implementation
// Template" (the Order example) from docs/reference.md into real code, then
// generalizes it into a reusable form by parameterizing only the domain name.
// Generates in one pass: an Aggregate (a single Status field) + CQRS
// Command/Query Handlers + one domain event + a Repository (domain interface +
// infrastructure implementation) + an HTTP Handler + a DTO.
//
// Like examples/harness, this directory is an independent Go module — it must
// be run from inside this directory (implementations/go/ has no go.mod/go.work
// tying them together).
//
// Usage (from inside scripts/create-domain/):
//
//	go run . <PascalCaseDomainName> [--out <targetRoot>] [--wire]
//
// Examples:
//
//	go run . Coupon
//	  -> generates under examples/internal/... (the script's default target); doesn't touch main.go/router.go
//	go run . Coupon --out /tmp/scratch-app --wire
//	  -> generates under the given root and auto-inserts the repository/handler/
//	     route into cmd/server/main.go and internal/interface/http/router.go
//
// Without --wire, main.go/router.go are left untouched and the content to paste
// in is only printed to the console — the default is the safe path (manual
// application), since a script arbitrarily editing a project's central
// assembly files may not be wanted (same default philosophy as nestjs's
// create-domain.js).
//
// Go doesn't have a dedicated Relay/Consumer per domain — outbox.Poller
// (publishing) and outbox.Consumer (receiving/executing) share the single
// map[string]outbox.Handler that main.go assembles, so adding a new domain
// entry to that handlers map in main.go is this generator's core wiring target
// (see wiring.go's comments for the detailed design). The Command Handler never
// references this map at all — it returns immediately after saving (synchronous
// draining is prohibited, see domain-events.md).
package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"strconv"
	"strings"
)

func usage() {
	fmt.Fprintln(os.Stderr, "Usage (from inside scripts/create-domain/): go run . <PascalCaseDomainName> [--out <targetRoot>] [--wire]")
}

// defaultTargetRoot points to ../../examples relative to this script file's own
// location — runtime.Caller(0) still returns the compile-time source path even
// when run via go run.
func defaultTargetRoot() string {
	_, thisFile, _, _ := runtime.Caller(0)
	scriptDir := filepath.Dir(thisFile)
	return filepath.Join(scriptDir, "..", "..", "examples")
}

// readModulePath reads the import path prefix from the first "module " line in
// targetRoot/go.mod.
func readModulePath(targetRoot string) (string, error) {
	data, err := os.ReadFile(filepath.Join(targetRoot, "go.mod"))
	if err != nil {
		return "", fmt.Errorf("failed to read go.mod: %w", err)
	}
	for _, line := range strings.Split(string(data), "\n") {
		line = strings.TrimSpace(line)
		if strings.HasPrefix(line, "module ") {
			return strings.TrimSpace(strings.TrimPrefix(line, "module ")), nil
		}
	}
	return "", fmt.Errorf("no module declaration found in go.mod")
}

var migrationFileRe = regexp.MustCompile(`^(\d+)_`)

// nextMigrationSeq returns the highest number + 1 found among filenames like
// 0001_xxx.sql under targetRoot/migrations/. Returns 1 if the migrations/
// directory doesn't exist.
func nextMigrationSeq(targetRoot string) int {
	entries, err := os.ReadDir(filepath.Join(targetRoot, "migrations"))
	if err != nil {
		return 1
	}
	max := 0
	for _, e := range entries {
		m := migrationFileRe.FindStringSubmatch(e.Name())
		if m == nil {
			continue
		}
		if v, err := strconv.Atoi(m[1]); err == nil && v > max {
			max = v
		}
	}
	return max + 1
}

func writeFile(path, content string) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	return os.WriteFile(path, []byte(content), 0o644)
}

// gofmtFiles runs gofmt -w on the generated/modified .go files to fix import
// ordering and formatting. Silently skipped if the gofmt binary isn't found
// (doesn't affect build/vet/harness results — formatting issues are caught
// separately by golangci-lint's gofmt formatter).
func gofmtFiles(paths []string) {
	gofmtBin, err := exec.LookPath("gofmt")
	if err != nil {
		fmt.Fprintf(os.Stderr, "warning: gofmt not found, skipping formatting: %v\n", err)
		return
	}
	for _, p := range paths {
		cmd := exec.Command(gofmtBin, "-w", p)
		if out, err := cmd.CombinedOutput(); err != nil {
			fmt.Fprintf(os.Stderr, "warning: gofmt -w %s failed: %v\n%s\n", p, err, out)
		}
	}
}

func main() {
	args := os.Args[1:]
	if len(args) == 0 || strings.HasPrefix(args[0], "--") {
		usage()
		os.Exit(1)
	}
	rawDomainName := args[0]

	targetRoot := defaultTargetRoot()
	shouldWire := false
	for i := 1; i < len(args); i++ {
		switch args[i] {
		case "--out":
			if i+1 >= len(args) {
				usage()
				os.Exit(1)
			}
			targetRoot = args[i+1]
			i++
		case "--wire":
			shouldWire = true
		default:
			fmt.Fprintf(os.Stderr, "unknown argument: %s\n", args[i])
			usage()
			os.Exit(1)
		}
	}

	absTargetRoot, err := filepath.Abs(targetRoot)
	if err != nil {
		fmt.Fprintf(os.Stderr, "failed to resolve target path: %v\n", err)
		os.Exit(1)
	}

	modulePath, err := readModulePath(absTargetRoot)
	if err != nil {
		fmt.Fprintf(os.Stderr, "%v\n", err)
		os.Exit(1)
	}

	n := BuildNames(rawDomainName, modulePath)
	migrationSeq := nextMigrationSeq(absTargetRoot)
	files := GenerateFiles(n, migrationSeq)

	var writtenGoFiles []string
	for relPath, content := range files {
		fullPath := filepath.Join(absTargetRoot, relPath)
		if err := writeFile(fullPath, content); err != nil {
			fmt.Fprintf(os.Stderr, "failed to write file (%s): %v\n", relPath, err)
			os.Exit(1)
		}
		if strings.HasSuffix(fullPath, ".go") {
			writtenGoFiles = append(writtenGoFiles, fullPath)
		}
	}

	fmt.Printf("%s domain generated: under %s (%d file(s))\n", n.Domain, absTargetRoot, len(files))
	fmt.Printf("REST paths: /%s (POST create, GET/{id} fetch, POST /{id}/cancel cancel)\n", n.DomainsLower)
	fmt.Println()
	fmt.Println("Note: a naive pluralization rule (+s / +es / y→ies) was used — for domains with")
	fmt.Printf("  irregular plurals, you may need to manually adjust %s (table/path names) etc.\n", n.DomainsLower)

	if shouldWire {
		result, err := WireMainAndRouter(absTargetRoot, n)
		if err != nil {
			fmt.Fprintf(os.Stderr, "wiring failed: %v\n", err)
			os.Exit(1)
		}
		switch {
		case result.AlreadyWired:
			fmt.Println("Already registered in main.go — skipping wiring.")
		default:
			fmt.Printf("Repository/handler/route registration complete — %d file(s) modified:\n", len(result.ChangedFiles))
			for _, f := range result.ChangedFiles {
				fmt.Printf("  %s\n", f)
			}
			writtenGoFiles = append(writtenGoFiles, result.ChangedFiles...)
		}
	} else {
		PrintWiringSnippet(n)
	}

	gofmtFiles(writtenGoFiles)

	fmt.Println("Next: verify the build with go build ./..., go vet ./..., then check compliance with bash harness.sh <projectRoot>.")
}

package main

import (
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

// checkSoftDeleteFilter — soft-delete-filter: docs/architecture/persistence.md
// forbids hard deletes and mandates that queries default to applying a
// `deletedAt IS NULL` (in Go, `deleted_at IS NULL`) condition.
//
// In this repository's actual schema, only a single table (accounts) has a
// deleted_at column in `migrations/*.sql` (see persistence.md's "known gaps"
// section — Card/Payment/Refund/Credential have no soft-delete use case yet,
// so the column does not exist at all). So this rule cannot simply check
// "every Find*/FindAll query must unconditionally include deleted_at IS
// NULL" — account_repository.go itself has FindAccounts (the accounts table,
// which has deleted_at) and FindTransactions (the transactions table, which
// does not) coexisting in the same file, so judging at the file level (e.g.
// "if deleted_at is mentioned anywhere in the file, enforce it on every Find*
// method in that file") would produce a false positive on FindTransactions.
//
// So this rule first determines, from the migration SQL, "which tables
// actually have a deleted_at column" (root/migrations/*.sql, excluding
// *.down.sql), then extracts the table name (`FROM <table>`) that each
// Repository's Find*/FindAll method SELECTs from, and requires the
// `deleted_at IS NULL` filter only when that table has deleted_at — methods
// targeting a table that has no such column at all are excluded from the
// check (not even SKIP — they are simply not mentioned, since the condition
// cannot apply to that table in the first place, so no noise is left in the
// findings).
var (
	createTableBlock  = regexp.MustCompile(`(?is)CREATE TABLE\s+(\w+)\s*\((.*?)\n\)\s*;`)
	alterAddDeletedAt = regexp.MustCompile(`(?is)ALTER TABLE\s+(\w+)\s+ADD COLUMN\s+deleted_at\b`)
	deletedAtColumn   = regexp.MustCompile(`(?i)\bdeleted_at\b`)

	repoFindMethodDecl = regexp.MustCompile(`(?m)^func\s+\(\w+\s+\*(\w+)\)\s+(Find\w*)\s*\(`)
	fromTable          = regexp.MustCompile(`(?is)FROM\s+(\w+)`)
	deletedAtIsNull    = regexp.MustCompile(`(?i)deleted_at\s+is\s+null`)
)

// tablesWithDeletedAt scans every non-down migration SQL file under root/migrations
// and returns the set of table names that have a deleted_at column (either
// declared inline in CREATE TABLE or added later via ALTER TABLE ADD COLUMN).
func tablesWithDeletedAt(root string) (map[string]bool, error) {
	tables := make(map[string]bool)
	migDir := filepath.Join(root, "migrations")
	entries, err := os.ReadDir(migDir)
	if err != nil {
		return tables, err
	}
	for _, e := range entries {
		if e.IsDir() || !strings.HasSuffix(e.Name(), ".sql") || strings.HasSuffix(e.Name(), ".down.sql") {
			continue
		}
		content, readErr := os.ReadFile(filepath.Join(migDir, e.Name()))
		if readErr != nil {
			continue
		}
		src := string(content)
		for _, m := range createTableBlock.FindAllStringSubmatch(src, -1) {
			table, body := m[1], m[2]
			if deletedAtColumn.MatchString(body) {
				tables[table] = true
			}
		}
		for _, m := range alterAddDeletedAt.FindAllStringSubmatch(src, -1) {
			tables[m[1]] = true
		}
	}
	return tables, nil
}

func checkSoftDeleteFilter(root string) RuleResult {
	result := RuleResult{Section: "soft-delete-filter"}

	softDeleteTables, migErr := tablesWithDeletedAt(root)
	if migErr != nil {
		result.Findings = append(result.Findings, skipFinding("no root/migrations/*.sql — cannot determine soft-delete target tables, skipping"))
		return result
	}
	if len(softDeleteTables) == 0 {
		result.Findings = append(result.Findings, skipFinding("no tables with a deleted_at column (per the migrations)"))
		return result
	}

	found := false
	walkErr := filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() || !strings.HasSuffix(path, ".go") {
			return nil
		}
		name := d.Name()
		if strings.HasSuffix(name, "_test.go") || !strings.HasSuffix(name, "_repository.go") {
			return nil
		}
		slashPath := filepath.ToSlash(path)
		if !strings.Contains(slashPath, "/internal/infrastructure/persistence/") {
			return nil
		}
		content, readErr := os.ReadFile(path)
		if readErr != nil {
			return nil
		}
		src := stripGoComments(string(content))
		rel, _ := filepath.Rel(root, path)

		for _, m := range repoFindMethodDecl.FindAllStringSubmatchIndex(src, -1) {
			recv := src[m[2]:m[3]]
			method := src[m[4]:m[5]]

			// Extract the body starting from the end of the method signature (the next '{').
			openBrace := strings.Index(src[m[1]:], "{")
			if openBrace == -1 {
				continue
			}
			bodyStart := m[1] + openBrace
			body := extractBalancedBlock(src, bodyStart, '{', '}')

			tableMatch := fromTable.FindStringSubmatch(body)
			if tableMatch == nil {
				continue // a Find* helper that does not SELECT (e.g. one that merely wraps another query)
			}
			table := tableMatch[1]
			if !softDeleteTables[table] {
				continue // this table has no deleted_at column at all — out of scope
			}

			found = true
			label := rel + " (" + recv + "." + method + " → " + table + ")"
			if deletedAtIsNull.MatchString(body) {
				result.Findings = append(result.Findings, passFinding(label))
			} else {
				result.Findings = append(result.Findings, failFinding(label,
					table+" table has a deleted_at column (per the migrations), but this query "+
						"does not include a deleted_at IS NULL filter — soft-deleted rows are exposed in the query result (docs/architecture/persistence.md)"))
			}
		}
		return nil
	})
	if walkErr != nil {
		result.Findings = append(result.Findings, failFinding(root, "directory walk failed: "+walkErr.Error()))
	} else if !found {
		result.Findings = append(result.Findings, skipFinding("no Find*/FindAll methods targeting a table with a deleted_at column"))
	}
	return result
}

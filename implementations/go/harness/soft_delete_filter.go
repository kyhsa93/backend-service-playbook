package main

import (
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

// checkSoftDeleteFilter — soft-delete-filter: docs/architecture/persistence.md는 hard
// delete를 금지하고 조회 시 기본적으로 `deletedAt IS NULL`(Go에서는 `deleted_at IS NULL`)
// 조건이 적용되어야 한다고 못박는다.
//
// 이 저장소의 실제 스키마는 `migrations/*.sql`에 딱 하나의 테이블(accounts)만
// deleted_at 컬럼을 갖는다(persistence.md의 "알려진 격차" 절 — Card/Payment/Refund/
// Credential은 소프트 삭제 유스케이스가 아직 없어 컬럼 자체가 없다). 그래서 이 규칙은
// "Find*/FindAll 쿼리는 무조건 deleted_at IS NULL을 포함해야 한다"처럼 단순하게 검사할
// 수 없다 — account_repository.go 안에도 FindAccounts(accounts 테이블, deleted_at
// 있음)와 FindTransactions(transactions 테이블, deleted_at 없음)가 같은 파일에
// 공존하므로, "파일 전체에 deleted_at 언급이 있으면 그 파일의 모든 Find* 메서드에
// 강제"처럼 파일 단위로 판단하면 FindTransactions를 오탐한다.
//
// 그래서 이 규칙은 마이그레이션 SQL에서 "어떤 테이블이 실제로 deleted_at 컬럼을
// 갖는가"를 먼저 파악한 뒤(root/migrations/*.sql, *.down.sql 제외), Repository의 각
// Find*/FindAll 메서드가 SELECT하는 테이블명(`FROM <table>`)을 뽑아 그 테이블이
// deleted_at을 가진 경우에만 `deleted_at IS NULL` 필터를 요구한다 — 컬럼 자체가 없는
// 테이블을 대상으로 하는 메서드는 검사 대상에서 제외한다(SKIP도 아니고 그냥
// 언급하지 않는다 — 애초에 그 테이블에 적용할 수 없는 조건이므로 findings에 노이즈로
// 남기지 않는다).
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
		result.Findings = append(result.Findings, skipFinding("root/migrations/*.sql 없음 — 소프트 삭제 대상 테이블을 판단할 수 없어 건너뜀"))
		return result
	}
	if len(softDeleteTables) == 0 {
		result.Findings = append(result.Findings, skipFinding("deleted_at 컬럼을 가진 테이블 없음(마이그레이션 기준)"))
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

			// 메서드 시그니처 끝(다음 '{')부터 본문을 뽑는다.
			openBrace := strings.Index(src[m[1]:], "{")
			if openBrace == -1 {
				continue
			}
			bodyStart := m[1] + openBrace
			body := extractBalancedBlock(src, bodyStart, '{', '}')

			tableMatch := fromTable.FindStringSubmatch(body)
			if tableMatch == nil {
				continue // SELECT를 하지 않는 Find* 헬퍼(예: 다른 조회를 감싸기만 하는 경우)
			}
			table := tableMatch[1]
			if !softDeleteTables[table] {
				continue // 이 테이블엔 애초에 deleted_at 컬럼이 없음 — 대상 아님
			}

			found = true
			label := rel + " (" + recv + "." + method + " → " + table + ")"
			if deletedAtIsNull.MatchString(body) {
				result.Findings = append(result.Findings, passFinding(label))
			} else {
				result.Findings = append(result.Findings, failFinding(label,
					table+" 테이블은 deleted_at 컬럼을 가지지만(마이그레이션 기준) 이 조회 쿼리가 "+
						"deleted_at IS NULL 필터를 포함하지 않음 — 소프트 삭제된 행이 조회에 노출된다(docs/architecture/persistence.md)"))
			}
		}
		return nil
	})
	if walkErr != nil {
		result.Findings = append(result.Findings, failFinding(root, "디렉토리 탐색 실패: "+walkErr.Error()))
	} else if !found {
		result.Findings = append(result.Findings, skipFinding("deleted_at 컬럼을 가진 테이블을 대상으로 하는 Find*/FindAll 메서드 없음"))
	}
	return result
}

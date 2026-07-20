package main

import (
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

// handleMethodSig matches a Query Handler's `Handle` method declaration and
// captures the success-branch return type (the first element of the
// `(<type>, error)` tuple). Handler methods in this repo always follow the
// `func (h *XHandler) Handle(ctx context.Context, q XQuery) (<ReturnType>, error) {`
// shape (see get_account_handler.go etc. and the create-domain scaffolding
// template's Get{{.Domain}}Handler), so a signature-line regex is enough —
// no need to parse the full method body.
var handleMethodSig = regexp.MustCompile(`(?m)^func\s+\([^)]+\)\s+Handle\([^)]*\)\s*\(([^,]+),\s*error\)`)

// domainQualifiedPointer matches a pointer type qualified by a package alias,
// e.g. `*account.Account` — the shape a raw domain Aggregate return type
// always takes from application/query (Aggregates are never dot-imported or
// referenced unqualified across packages in this repo).
var domainQualifiedPointer = regexp.MustCompile(`^\*(\w+)\.(\w+)$`)

// checkQueryHandlerNoRawAggregate — query-handler-no-raw-aggregate:
// docs/architecture/api-response.md, "Result 객체 설계" — Query Service(Handler)는
// Result/DTO 객체를 반환해야 하고 도메인 Aggregate를 직접 반환하지 않는다("Aggregate는
// 비즈니스 로직과 내부 상태를 포함한다. 직렬화하면 내부 구현이 외부에 노출된다").
//
// internal/application/query/*.go의 각 Handle 메서드가 성공 시 반환하는 타입이
// 그 파일이 import하는 internal/domain/<bc> 패키지로 한정된(qualified) 포인터
// 타입(예: *account.Account)이면 실패로 본다. 특정 타입 이름(Account/Card/Payment/
// Refund)을 하드코딩하지 않고 "domain 패키지로 qualify된 포인터 타입이면 전부 위반"
// 이라는 일반 규칙을 쓴다 — Query Handler가 반환할 정당한 타입은 언제나 그 Handler와
// 같은 query 패키지에 있는 전용 Result 타입(GetAccountResult 등, 패키지 한정자 없음)
// 뿐이고, 다른 domain 패키지의 Value Object를 그대로 반환하는 패턴도 이 저장소에는
// 없다 — 그래서 이 하드코딩 없는 형태가 새 도메인(create-domain 스캐폴딩 등)에도
// 그대로 일반화된다. Command Handler(예: Create{{.Domain}}Handler가 생성 직후의
// Aggregate를 그대로 반환하는 패턴)는 이 규칙의 대상이 아니다 — root 문서는 Query
// 응답에 한해 Aggregate 직접 노출을 금지한다.
func checkQueryHandlerNoRawAggregate(root string) RuleResult {
	result := RuleResult{Section: "query-handler-no-raw-aggregate"}
	queryDir := filepath.Join(root, "internal", "application", "query")
	if _, err := os.Stat(queryDir); os.IsNotExist(err) {
		result.Findings = append(result.Findings, skipFinding("internal/application/query/ 없음"))
		return result
	}

	found := false
	walkErr := filepath.WalkDir(queryDir, func(path string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() || !strings.HasSuffix(path, ".go") || strings.HasSuffix(d.Name(), "_test.go") {
			return nil
		}
		content, readErr := os.ReadFile(path)
		if readErr != nil {
			return nil
		}
		rel, _ := filepath.Rel(root, path)
		src := stripGoComments(string(content))

		sigMatches := handleMethodSig.FindAllStringSubmatch(src, -1)
		if len(sigMatches) == 0 {
			return nil // 이 파일에 Handle 메서드가 없음(result.go 등)
		}

		imports, parseErr := fileImportPaths(path)
		if parseErr != nil {
			found = true
			result.Findings = append(result.Findings, failFinding(rel, "Go 파일 파싱 실패: "+parseErr.Error()))
			return nil
		}
		domainAliases := map[string]bool{}
		for _, imp := range imports {
			if m := domainImportBC.FindStringSubmatch("/" + filepath.ToSlash(imp)); m != nil {
				domainAliases[m[1]] = true
			}
		}

		for _, sig := range sigMatches {
			found = true
			returnType := strings.TrimSpace(sig[1])
			m := domainQualifiedPointer.FindStringSubmatch(returnType)
			if m != nil && domainAliases[m[1]] {
				result.Findings = append(result.Findings, failFinding(rel,
					"Handle()이 domain 패키지의 raw 타입("+returnType+")을 그대로 반환함 — 전용 Result/DTO 타입을 반환해야 한다(docs/architecture/api-response.md)"))
			} else {
				result.Findings = append(result.Findings, passFinding(rel+" (Handle → "+returnType+")"))
			}
		}
		return nil
	})
	if walkErr != nil {
		result.Findings = append(result.Findings, failFinding(queryDir, "디렉토리 탐색 실패: "+walkErr.Error()))
	} else if !found {
		result.Findings = append(result.Findings, skipFinding("application/query/ 안에 Handle 메서드 없음"))
	}
	return result
}

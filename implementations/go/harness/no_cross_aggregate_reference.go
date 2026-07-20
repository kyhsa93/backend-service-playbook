package main

import (
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

// checkNoCrossAggregateReference — [12] no-cross-aggregate-reference:
// domain-service.md — "하나의 Aggregate는 다른 Aggregate를 직접 참조하지 않는다"
// (ID 문자열 참조는 허용, struct 타입 참조는 금지).
//
// 이 저장소에서 같은 Bounded Context 안에 서로 다른 Aggregate 두 개가 공존하는
// 유일한 실제 사례가 internal/domain/payment/(Payment, Refund)이므로, 이 규칙은
// 그 두 파일에 정확히 한정한다(payment.go가 Refund 타입을, refund.go가 Payment
// 타입을 struct 필드로 갖지 않는지). 일반화한 "Aggregate 탐지" 휴리스틱(예: 파일명
// 규칙만으로 Aggregate 여부 판단)은 다른 BC/파일에 오탐할 위험이 커서 지금은 이
// 알려진 실제 사례만 정밀 타겟팅한다 — Payment/Refund 둘 다 있는 한, 회귀를 잡는
// 가드로 충분하다.
type crossAggregateCheck struct {
	file          string // BC 루트 기준 상대 경로
	aggregateName string
	forbiddenType string
}

var crossAggregateChecks = []crossAggregateCheck{
	{file: "payment.go", aggregateName: "Payment", forbiddenType: "Refund"},
	{file: "refund.go", aggregateName: "Refund", forbiddenType: "Payment"},
}

// checkNoCrossAggregateReference — payment BC 디렉토리를 찾아 두 파일을 검사한다.
func checkNoCrossAggregateReference(root string) RuleResult {
	result := RuleResult{Section: "no-cross-aggregate-reference"}
	paymentDir := findDirNamed(root, "payment", "/internal/domain/")
	if paymentDir == "" {
		result.Findings = append(result.Findings, skipFinding("internal/domain/payment/ 없음(Payment/Refund 공존 BC 없음)"))
		return result
	}

	found := false
	for _, check := range crossAggregateChecks {
		path := filepath.Join(paymentDir, check.file)
		content, err := os.ReadFile(path)
		if err != nil {
			continue // 이 BC가 Payment/Refund 명명을 그대로 안 쓰면 조용히 skip
		}
		found = true
		rel, _ := filepath.Rel(root, path)
		src := string(content)

		structBody, ok := extractStructBody(src, check.aggregateName)
		if !ok {
			result.Findings = append(result.Findings, failFinding(rel, "type "+check.aggregateName+" struct 선언을 찾을 수 없음"))
			continue
		}
		if structFieldTypeRef(check.forbiddenType).MatchString(structBody) {
			result.Findings = append(result.Findings, failFinding(rel,
				check.aggregateName+" Aggregate가 "+check.forbiddenType+" Aggregate를 struct 필드로 직접 참조함 — Aggregate는 다른 Aggregate를 ID 문자열로만 참조해야 한다(domain-service.md)"))
		} else {
			result.Findings = append(result.Findings, passFinding(rel+" ("+check.aggregateName+")"))
		}
	}
	if !found {
		result.Findings = append(result.Findings, skipFinding("payment.go/refund.go 없음"))
	}
	return result
}

// structFieldTypeRef는 struct 필드 선언에서 타입이 정확히 typeName(포인터/슬라이스
// 포함)인 줄만 매칭한다 — "RefundID string"처럼 필드 이름에 타입명이 우연히 포함된
// 경우(타입은 string)는 매칭하지 않는다. 필드 선언은 `<필드명> <타입>` 형태로, 타입
// 컬럼이 정확히 typeName이어야 한다.
func structFieldTypeRef(typeName string) *regexp.Regexp {
	return regexp.MustCompile(`(?m)^\s*\w+\s+(?:\*|\[\]\*?)?` + regexp.QuoteMeta(typeName) + `\b`)
}

// extractStructBody는 `type <name> struct {` 다음부터 컬럼 0의 다음 `}`까지를
// 본문으로 잘라낸다(repository_naming.go의 extractInterfaceBody와 동일한 근사 —
// 이 저장소의 Aggregate struct 필드 선언에는 중첩 `{}`가 없다).
func extractStructBody(src, typeName string) (string, bool) {
	decl := regexp.MustCompile(`(?m)^type\s+` + regexp.QuoteMeta(typeName) + `\s+struct\s*\{`)
	loc := decl.FindStringIndex(src)
	if loc == nil {
		return "", false
	}
	return extractInterfaceBody(src, loc[1]), true
}

// findDirNamed는 root 아래에서 pathContains를 경로에 포함하고 이름이 name인
// 디렉토리를 첫 번째로 찾아 반환한다(없으면 빈 문자열).
func findDirNamed(root, name, pathContains string) string {
	var found string
	_ = filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
		if err != nil || !d.IsDir() || found != "" {
			return nil
		}
		slashPath := filepath.ToSlash(path)
		if d.Name() == name && strings.Contains(slashPath+"/", pathContains) {
			found = path
		}
		return nil
	})
	return found
}

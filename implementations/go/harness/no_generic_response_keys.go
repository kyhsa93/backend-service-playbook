package main

import (
	"os"
	"path/filepath"
	"strings"
)

// forbiddenResponseKeys — root docs/architecture/api-response.md, "목록 조회 응답 형식":
// 목록 응답의 키 이름은 도메인 객체명 복수형(orders/accounts/payments)이어야 하고,
// result/data/items 같은 범용 키는 금지한다.
var forbiddenResponseKeys = map[string]bool{
	"result": true,
	"data":   true,
	"items":  true,
}

// checkNoGenericResponseKeys — no-generic-response-keys: internal/interface/http/**의
// struct 선언 중 json 태그가 정확히 "result"/"data"/"items"인 필드를 찾는다. 이런
// 범용 키는 도메인 객체명 복수형(orders/accounts/payments 등)으로 바꿔야 한다.
//
// error_response_schema.go와 동일하게 struct 선언 + json 태그를 구조적으로 파싱한다
// (extractBalancedBlock으로 struct 본문을 잘라낸 뒤 jsonTagLine으로 태그를 뽑는다) —
// 두 규칙 모두 같은 패키지의 structDecl/jsonTagLine 정규식을 재사용한다.
//
// "items"는 root 문서의 "단건 조회 응답 형식" 예시에서 한 리소스에 딸린 하위 목록
// (주문의 line item 등)을 가리키는 필드명으로도 등장하지만, 그 예시조차 "범용 키
// 사용 금지" 원칙이 최상위 목록 응답에 적용됨을 보여줄 뿐 "items"라는 이름 자체를
// 예외로 허용하지는 않는다 — 이 저장소의 실제 DTO(GetPaymentsResponse.Payments,
// GetRefundsResponse.Refunds, GetTransactionsResponse.Transactions 등)는 모두 이미
// 도메인 복수형을 쓰므로, "items" 같은 하위 목록이 필요해지면 그때도 도메인 명사
// (orderItems 등)를 써야 한다 — 필드명 무관 일괄 금지가 이 규칙의 의도다.
func checkNoGenericResponseKeys(root string) RuleResult {
	result := RuleResult{Section: "no-generic-response-keys"}
	found := false
	walkErr := filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() || !strings.HasSuffix(path, ".go") {
			return nil
		}
		if strings.HasSuffix(d.Name(), "_test.go") {
			return nil
		}
		slashPath := filepath.ToSlash(path)
		if !strings.Contains(slashPath, "/internal/interface/") {
			return nil
		}
		content, readErr := os.ReadFile(path)
		if readErr != nil {
			return nil
		}
		src := stripGoComments(string(content))
		rel, _ := filepath.Rel(root, path)

		matches := structDecl.FindAllStringSubmatchIndex(src, -1)
		for _, m := range matches {
			name := src[m[2]:m[3]]
			bodyStart := m[1] - 1 // '{' 위치
			body := extractBalancedBlock(src, bodyStart, '{', '}')

			tagMatches := jsonTagLine.FindAllStringSubmatch(body, -1)
			var bad []string
			for _, tm := range tagMatches {
				key := strings.Split(tm[1], ",")[0]
				if forbiddenResponseKeys[key] {
					bad = append(bad, key)
				}
			}
			if len(bad) == 0 {
				continue
			}
			found = true
			result.Findings = append(result.Findings, failFinding(rel+" ("+name+")",
				"목록 응답 필드에 범용 키("+strings.Join(bad, ", ")+")를 사용함 — 도메인 객체명 복수형(orders/accounts/payments 등)을 써야 한다(docs/architecture/api-response.md)"))
		}
		return nil
	})
	if walkErr != nil {
		result.Findings = append(result.Findings, failFinding(root, "디렉토리 탐색 실패: "+walkErr.Error()))
		return result
	}
	if !found {
		result.Findings = append(result.Findings, passFinding("internal/interface/**의 응답 struct에 범용 키(result/data/items) 없음"))
	}
	return result
}

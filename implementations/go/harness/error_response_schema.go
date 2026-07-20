package main

import (
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
)

// checkErrorResponseSchema — error-response-schema: docs/architecture/error-handling.md는
// 모든 에러 응답이 정확히 4개 필드(statusCode/code/message/error)만 갖도록 못박는다
// ({"statusCode":404,"code":"ORDER_NOT_FOUND","message":"...","error":"Not Found"}).
//
// internal/interface/http/**의 struct 선언 중 `json:"statusCode"` 태그를 가진 것을
// "에러 응답 struct" 후보로 삼는다 — 다른 DTO(CreateAccountRequest 등)까지 모두
// "정확히 4개 필드여야 한다"고 검사하면 전혀 무관한 정상 DTO가 대량으로 오탐하므로,
// statusCode 필드를 가진 struct만 표적으로 좁힌다. 후보로 잡힌 struct는 json 태그
// 집합이 정확히 {statusCode, code, message, error}와 일치해야 한다 — 더 많아도(예:
// timestamp 필드 추가), 더 적어도(예: error 필드 누락), 이름이 달라도(예: errorCode)
// 위반이다.
//
// 이 저장소는 middleware 패키지가 interface/http 패키지를 import하면 순환 참조가
// 되는 제약 때문에(순환 방지, module-pattern.md) rate_limit_middleware.go가 스키마만
// 동일하게 복제한 rateLimitErrorResponse를 별도로 갖는다 — 이 규칙은 struct 선언
// 위치·이름을 가리지 않고 internal/interface/**의 모든 구조체를 각각 독립적으로
// 검사하므로 이런 "같은 스키마의 복제 struct" 패턴도 자연스럽게 커버한다.
var (
	structDecl  = regexp.MustCompile(`(?ms)^type\s+(\w+)\s+struct\s*\{`)
	jsonTagLine = regexp.MustCompile(`json:"([^"]*)"`)
)

var wantErrorResponseFields = []string{"statusCode", "code", "message", "error"}

func checkErrorResponseSchema(root string) RuleResult {
	result := RuleResult{Section: "error-response-schema"}
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
			var fields []string
			hasStatusCode := false
			for _, tm := range tagMatches {
				key := strings.Split(tm[1], ",")[0]
				if key == "-" || key == "" {
					continue
				}
				if key == "statusCode" {
					hasStatusCode = true
				}
				fields = append(fields, key)
			}
			if !hasStatusCode {
				continue // 에러 응답 struct 후보가 아님(statusCode 필드 없음)
			}
			found = true

			if reason := errorResponseSchemaViolation(fields); reason != "" {
				result.Findings = append(result.Findings, failFinding(rel+" ("+name+")", reason))
			} else {
				result.Findings = append(result.Findings, passFinding(rel+" ("+name+")"))
			}
		}
		return nil
	})
	if walkErr != nil {
		result.Findings = append(result.Findings, failFinding(root, "디렉토리 탐색 실패: "+walkErr.Error()))
	} else if !found {
		result.Findings = append(result.Findings, skipFinding("json:\"statusCode\" 태그를 가진 에러 응답 struct 없음"))
	}
	return result
}

// errorResponseSchemaViolation은 fields가 wantErrorResponseFields와 정확히 일치하는지
// 검사한다(순서 무관, 개수·이름 모두 일치해야 함). 위반이 아니면 빈 문자열을 반환한다.
func errorResponseSchemaViolation(fields []string) string {
	want := append([]string(nil), wantErrorResponseFields...)
	sort.Strings(want)
	got := append([]string(nil), fields...)
	sort.Strings(got)

	if len(got) == len(want) {
		match := true
		for i := range got {
			if got[i] != want[i] {
				match = false
				break
			}
		}
		if match {
			return ""
		}
	}

	missing := diffFields(want, got)
	extra := diffFields(got, want)
	reason := "json 필드 구성이 표준 에러 응답 스키마(statusCode/code/message/error)와 다름"
	if len(missing) > 0 {
		reason += " — 누락: " + strings.Join(missing, ", ")
	}
	if len(extra) > 0 {
		reason += " — 초과/이름 다름: " + strings.Join(extra, ", ")
	}
	return reason + "(docs/architecture/error-handling.md)"
}

// diffFields는 a에는 있지만 b에는 없는 원소를 반환한다(중복 무시).
func diffFields(a, b []string) []string {
	inB := make(map[string]bool, len(b))
	for _, x := range b {
		inB[x] = true
	}
	seen := make(map[string]bool)
	var out []string
	for _, x := range a {
		if !inB[x] && !seen[x] {
			out = append(out, x)
			seen[x] = true
		}
	}
	return out
}

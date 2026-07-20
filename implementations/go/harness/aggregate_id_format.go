package main

import (
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

// checkAggregateIDFormat — aggregate-id-format: docs/architecture/aggregate-id.md는
// Aggregate ID를 "UUID v4에서 하이픈을 제거한 32자리 hex 문자열"로 못박는다 — 하이픈이
// 있는 원본 UUID 문자열(`550e8400-e29b-41d4-a716-446655440000`)을 그대로 쓰는 것은
// 잘못된 방식이라고 문서가 명시적으로 예시까지 든다. 이 저장소의 실제 ID 발급
// 유틸(`internal/common/id.go`의 `common.NewID()`)이 실제로 하이픈을 제거하고 있는지
// 검사한다 — 단일 파일 검사이며, 이 저장소가 자체 개발한 새 도메인의 Aggregate 생성자가
// common.NewID()를 호출하는지는 다른 규칙(repository-naming 등과 마찬가지로 이 저장소는
// "Aggregate ID는 어디서든 이 한 유틸을 거친다"는 전제를 architecture 문서로만 강제하고
// 기계적으로는 강제하지 않는다 — 새 유틸을 여러 개 만들면 이 규칙은 처음 찾은 것만 본다)는
// 다루지 않는다.
//
// NewID를 찾으면, 함수 본문이:
//   - 하이픈 제거 신호(strings.ReplaceAll(..., "-", ...) 류)가 있으면 PASS.
//   - uuid 패키지를 호출하면서 하이픈 제거 신호가 전혀 없으면(즉 uuid.NewString()/
//     uuid.New().String()을 가공 없이 그대로 반환) FAIL — 하이픈 있는 UUID를 그대로
//     내보낸다.
//   - uuid 패키지도 안 쓰고 하이픈 제거 신호도 없으면(예: crypto/rand + hex.EncodeToString
//     처럼 애초에 하이픈이 생길 수 없는 다른 생성 방식) PASS — hex.EncodeToString이
//     하이픈을 만들 수 없다는 것 자체가 안전 신호다.
//   - 그 외(무엇을 하는지 텍스트 검색으로 판단 불가)는 FAIL로 처리해 놓친 위반을
//     통과시키지 않는다(보수적 기본값).
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
		result.Findings = append(result.Findings, failFinding(root, "디렉토리 탐색 실패: "+walkErr.Error()))
		return result
	}
	if candidate == "" {
		result.Findings = append(result.Findings, skipFinding("func NewID() string 를 찾을 수 없음(internal/common/id.go 예상 위치) — docs/architecture/aggregate-id.md"))
		return result
	}

	content, readErr := os.ReadFile(candidate)
	if readErr != nil {
		result.Findings = append(result.Findings, failFinding(candidate, "파일 읽기 실패: "+readErr.Error()))
		return result
	}
	src := stripGoComments(string(content))
	rel, _ := filepath.Rel(root, candidate)

	loc := newIDFuncDecl.FindStringIndex(src)
	braceIdx := loc[1] - 1 // newIDFuncDecl 자체가 마지막에 '{'로 끝나므로 그 위치
	body := extractBalancedBlock(src, braceIdx, '{', '}')

	usesUUID := uuidCallSignal.MatchString(body)
	stripsHyphen := hyphenStripHex.MatchString(body)
	usesHexEncode := hexEncodeSafe.MatchString(body)

	switch {
	case stripsHyphen:
		result.Findings = append(result.Findings, passFinding(rel+" (NewID — 하이픈 제거 확인)"))
	case usesUUID:
		result.Findings = append(result.Findings, failFinding(rel,
			"NewID()가 uuid 패키지를 호출하지만 하이픈을 제거하는 코드(strings.ReplaceAll 등)가 없음 — "+
				"UUID v4에서 하이픈을 제거한 32자리 hex 문자열을 반환해야 한다(docs/architecture/aggregate-id.md)"))
	case usesHexEncode:
		result.Findings = append(result.Findings, passFinding(rel+" (NewID — hex.EncodeToString 기반, 하이픈 생성 불가)"))
	default:
		result.Findings = append(result.Findings, failFinding(rel,
			"NewID() 구현에서 하이픈 제거 여부를 확인할 수 없음 — "+
				"UUID v4에서 하이픈을 제거한 32자리 hex 문자열을 반환해야 한다(docs/architecture/aggregate-id.md)"))
	}

	return result
}

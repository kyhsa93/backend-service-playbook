package main

import (
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

// checkOutboxDrainOrder — [8] Outbox 드레인 순서 검증 — domain-events.md의 핵심 불변식
//
// OutboxRelay를 의존성으로 갖는 Command Handler는 저장(Save) 커밋 이후
// 반드시 ProcessPending을 호출해 Outbox를 드레인해야 한다(domain-events.md).
// 이 검사는 파일명·배치가 아니라 실제 메서드 본문을 본다 — Save() 호출 뒤에
// ProcessPending() 호출이 텍스트 순서상 등장하는지 확인한다(AST는 아니지만,
// 같은 함수 본문 안에서 두 호출의 상대적 순서를 근사적으로 검증하기에는
// 충분하다). 이 규칙이 없으면 dual-write 시절 패턴으로 회귀해도
// (ProcessPending 호출 삭제, 또는 알림을 직접 호출하는 것으로 되돌려도)
// 다른 어떤 규칙도 이를 잡아내지 못한다.
var (
	// \.Save\w*\( — Save(...) 자체뿐 아니라 SaveRefund(...)/SaveAccount(...)처럼 같은
	// 패키지 안에 저장 대상이 여럿이라 이름이 갈리는 도메인 특화 변형도 인정한다
	// (예: internal/domain/payment가 Payment+Refund 두 Aggregate를 갖고 있어
	// Repository.Save/RefundRepository.SaveRefund로 이름이 나뉜 경우).
	saveCall           = regexp.MustCompile(`\.Save\w*\(`)
	processPendingCall = regexp.MustCompile(`\.ProcessPending\(`)
	blockComment       = regexp.MustCompile(`(?s)/\*.*?\*/`)
	lineComment        = regexp.MustCompile(`//[^\n]*`)
)

// stripGoComments는 라인/블록 주석을 제거한 소스를 반환한다. 이 파일의 검사들은 AST가
// 아니라 텍스트 검색(strings.Contains, 정규식)에 의존하므로, 설계 의도를 설명하는 주석문
// 안에서 "OutboxRelay" 같은 식별자를 언급하는 것만으로 실제 의존성이 있다고 잘못
// 판단해서는 안 된다(예: "OutboxRelay를 주입받지 않는 이유는..."처럼 실제로는 그 타입에
// 의존하지 않음을 설명하는 주석). 문자열 리터럴 안의 "//"/"/*"는 이 저장소의 실제
// 소스에서 이런 종류의 오탐을 만들 만큼 흔하지 않으므로, 완전한 파서 대신 이 근사치로 충분하다.
func stripGoComments(src string) string {
	src = blockComment.ReplaceAllString(src, "")
	src = lineComment.ReplaceAllString(src, "")
	return src
}

func checkOutboxDrainOrder(root string) RuleResult {
	result := RuleResult{Section: "outbox-drain-order"}
	found := false
	walkErr := filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() || !strings.HasSuffix(path, ".go") {
			return nil
		}
		name := d.Name()
		if strings.HasSuffix(name, "_test.go") ||
			!strings.HasSuffix(name, "_handler.go") ||
			strings.HasSuffix(name, "_event_handler.go") {
			return nil
		}
		content, readErr := os.ReadFile(path)
		if readErr != nil {
			return nil
		}
		src := stripGoComments(string(content))
		if !strings.Contains(src, "OutboxRelay") {
			// 이 핸들러는 OutboxRelay를 의존성으로 갖지 않음 — 대상 아님(예: 조회 전용 핸들러가 이 디렉토리에 있는 경우 등)
			return nil
		}
		found = true
		rel, _ := filepath.Rel(root, path)

		saveLoc := saveCall.FindStringIndex(src)
		ppLoc := processPendingCall.FindStringIndex(src)
		switch {
		case saveLoc == nil:
			result.Findings = append(result.Findings, failFinding(rel, "OutboxRelay를 참조하지만 Save(...) 호출을 찾을 수 없음"))
		case ppLoc == nil:
			result.Findings = append(result.Findings, failFinding(rel, "OutboxRelay를 참조하지만 ProcessPending(...) 호출이 없음 — 저장 직후 Outbox 드레인 누락(domain-events.md)"))
		case ppLoc[0] < saveLoc[0]:
			result.Findings = append(result.Findings, failFinding(rel, "ProcessPending(...) 호출이 Save(...) 호출보다 먼저 등장함 — 커밋 이후 드레인 순서 위반"))
		default:
			result.Findings = append(result.Findings, passFinding(rel+" (Save → ProcessPending 순서 확인)"))
		}
		return nil
	})
	if walkErr != nil {
		result.Findings = append(result.Findings, failFinding(root, "디렉토리 탐색 실패: "+walkErr.Error()))
	} else if !found {
		result.Findings = append(result.Findings, skipFinding("OutboxRelay를 사용하는 Command Handler 없음"))
	}
	return result
}

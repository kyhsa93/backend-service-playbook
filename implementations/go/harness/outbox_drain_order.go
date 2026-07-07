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
	saveCall           = regexp.MustCompile(`\.Save\(`)
	processPendingCall = regexp.MustCompile(`\.ProcessPending\(`)
)

func checkOutboxDrainOrder(root string) RuleResult {
	result := RuleResult{Section: "outbox-drain-order"}
	found := false
	filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
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
		src := string(content)
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
	if !found {
		result.Findings = append(result.Findings, skipFinding("OutboxRelay를 사용하는 Command Handler 없음"))
	}
	return result
}

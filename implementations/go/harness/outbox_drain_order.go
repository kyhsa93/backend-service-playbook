package main

import (
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

// checkOutboxDrainOrder — [8] 동기 드레인 금지 검증 — domain-events.md의 핵심 불변식
//
// Outbox → SQS 발행은 독립적으로 주기 실행되는 outbox.Poller가, SQS → Handler 실행은
// outbox.Consumer가 각자 맡는다(main.go의 goroutine). Command Handler는 저장 후 곧바로
// 반환해야 하며, OutboxRelay/OutboxPoller/OutboxConsumer를 참조하거나 드레인
// 메서드(ProcessPending/Poll/drainOnce)를 호출하면 안 된다. 이 검사가 없으면 누군가
// Command Handler에 드레인 호출을 추가해도 잡아내지 못한다.
var (
	// OutboxRelay/OutboxPoller/OutboxConsumer/outbox.Poller/outbox.Consumer/outbox.Relay
	// 참조를 모두 금지한다 — 어떤 이름으로 동기 드레인이 재도입되든 잡는다.
	forbiddenSymbol = regexp.MustCompile(
		`\bOutboxRelay\b|\bOutboxPoller\b|\bOutboxConsumer\b|\boutbox\.Relay\b|\boutbox\.Poller\b|\boutbox\.Consumer\b`,
	)
	// .ProcessPending(/.Poll(/.drainOnce( 등 드레인 메서드 호출.
	forbiddenCall = regexp.MustCompile(`\.\s*(?:ProcessPending|Poll|drainOnce)\s*\(`)
	saveCall      = regexp.MustCompile(`\.Save\w*\(`)
	blockComment  = regexp.MustCompile(`(?s)/\*.*?\*/`)
	lineComment   = regexp.MustCompile(`//[^\n]*`)
)

// stripGoComments는 라인/블록 주석을 제거한 소스를 반환한다. 이 파일의 검사들은 AST가
// 아니라 텍스트 검색(strings.Contains, 정규식)에 의존하므로, 설계 의도를 설명하는 주석문
// 안에서 "OutboxRelay" 같은 식별자를 언급하는 것만으로 실제 의존성이 있다고 잘못
// 판단해서는 안 된다(예: "이 Handler는 outbox.Poller/outbox.Consumer를 직접 참조하지
// 않는다"처럼 실제로는 그 타입에 의존하지 않음을 설명하는 주석). 문자열 리터럴 안의
// "//"/"/*"는 이 저장소의 실제 소스에서 이런 종류의 오탐을 만들 만큼 흔하지 않으므로,
// 완전한 파서 대신 이 근사치로 충분하다.
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
		rel, _ := filepath.Rel(root, path)

		if forbiddenSymbol.MatchString(src) {
			found = true
			result.Findings = append(result.Findings, failFinding(rel,
				"OutboxRelay/OutboxPoller/OutboxConsumer를 참조함 — Command Handler는 저장 후 곧바로 반환해야 하며, Outbox → SQS 발행/수신은 독립적으로 주기 실행되는 outbox.Poller/outbox.Consumer만의 책임이다(동기 드레인 금지, domain-events.md)"))
			return nil
		}
		if forbiddenCall.MatchString(src) {
			found = true
			result.Findings = append(result.Findings, failFinding(rel,
				"ProcessPending()/Poll()/drainOnce() 등 드레인 메서드를 호출함 — Command Handler는 저장 후 곧바로 반환해야 한다(동기 드레인 금지, domain-events.md)"))
			return nil
		}
		// Save(...) 호출이 있는(=상태를 변경하는) Handler인데 금지 심볼/호출이 전혀 없으면
		// "올바르게 드레인하지 않는다"로 Pass 처리한다 — Query Handler 등 Save가 없는
		// 파일은 이 규칙의 대상이 아니므로 조용히 skip한다.
		if saveCall.MatchString(src) {
			found = true
			result.Findings = append(result.Findings, passFinding(rel+" (Save 이후 동기 드레인 호출 없음 확인)"))
		}
		return nil
	})
	if walkErr != nil {
		result.Findings = append(result.Findings, failFinding(root, "디렉토리 탐색 실패: "+walkErr.Error()))
	} else if !found {
		result.Findings = append(result.Findings, skipFinding("Save(...)를 호출하는 Command Handler 없음"))
	}
	return result
}

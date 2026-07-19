package main

import (
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

// checkSharedInfra — [6] shared-infra: outbox·task-queue 패턴
//
// outbox/task-queue 관련 코드가 있다면, 그 코드가 전용 디렉토리(디렉토리명이
// 정확히 "outbox"/"task-queue")에 모여 있고, 그 디렉토리가 실제로 해당 패턴의
// 핵심 타입을 구현하고 있어야 한다. shared-modules.md는 이 디렉토리의 위치를
// internal/ 바로 아래로 고정하지 않는다 — 여러 관심사별 하위 패키지
// (internal/infrastructure/outbox/ 등) 어디에 두어도 되므로, internal/ 전체를
// 재귀적으로 뒤져 확인한다.
//
// "outbox 패턴이 쓰이고 있는가"는 domain-events.md가 Repository 구현체(및 Application
// EventHandler)의 트랜잭션 저장 지점으로 규정하는 outbox.Writer(internal/infrastructure/
// persistence/ 등)의 실제 참조 여부로 판단한다 — 파일명에 우연히 "outbox" 문자열이
// 들어간 무관한 파일(예: migrations/000X_add_outbox.sql)에 낚이지 않기 위함이다.
// 2026-07 async 전환 전에는 command 패키지의 OutboxRelay 참조로 게이트를 판단했지만,
// 동기 드레인 금지 이후 Command Handler는 outbox 관련 타입을 전혀 참조하지 않게
// 됐으므로 그 신호가 사라졌다 — 대신 항상 남아있는 Repository ↔ outbox.Writer 연결로
// 게이트를 옮겼다.
func checkSharedInfra(root string) RuleResult {
	result := RuleResult{Section: "shared-infra"}
	result.Findings = append(result.Findings, checkOutboxPattern(root)...)
	result.Findings = append(result.Findings, checkTaskQueuePattern(root)...)
	return result
}

func checkOutboxPattern(root string) []Finding {
	usesOutboxWriter := false
	var outboxDirs []string
	walkErr := filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
		if err != nil {
			return nil
		}
		if d.IsDir() {
			if d.Name() == "outbox" {
				outboxDirs = append(outboxDirs, path)
			}
			return nil
		}
		if !strings.HasSuffix(path, ".go") || strings.HasSuffix(path, "_test.go") {
			return nil
		}
		content, readErr := os.ReadFile(path)
		if readErr != nil {
			return nil
		}
		if strings.Contains(string(content), "outbox.Writer") || strings.Contains(string(content), "outbox.NewWriter") {
			usesOutboxWriter = true
		}
		return nil
	})
	if walkErr != nil {
		return []Finding{failFinding(root, "디렉토리 탐색 실패: "+walkErr.Error())}
	}

	if !usesOutboxWriter {
		return []Finding{skipFinding("outbox 패턴 없음")}
	}

	if len(outboxDirs) == 0 {
		return []Finding{failFinding("internal/**/outbox/", "outbox.Writer를 참조하지만 전용 outbox/ 디렉토리가 없음")}
	}

	// Relay(동기 드레인)는 2026-07 async 전환으로 제거됐다 — 대신 Poller(Outbox → SQS
	// 발행)와 Consumer(SQS → Handler 실행)가 그 역할을 나눠 맡는다(domain-events.md).
	hasWriter, hasPoller, hasConsumer := false, false, false
	for _, dir := range outboxDirs {
		walkErr := filepath.WalkDir(dir, func(path string, d os.DirEntry, err error) error {
			if err != nil || d.IsDir() || !strings.HasSuffix(path, ".go") {
				return nil
			}
			content, readErr := os.ReadFile(path)
			if readErr != nil {
				return nil
			}
			src := string(content)
			if regexp.MustCompile(`(?m)^type\s+Writer\s+struct\b`).MatchString(src) {
				hasWriter = true
			}
			if regexp.MustCompile(`(?m)^type\s+Poller\s+struct\b`).MatchString(src) {
				hasPoller = true
			}
			if regexp.MustCompile(`(?m)^type\s+Consumer\s+struct\b`).MatchString(src) {
				hasConsumer = true
			}
			return nil
		})
		if walkErr != nil {
			return []Finding{failFinding(dir, "디렉토리 탐색 실패: "+walkErr.Error())}
		}
	}

	if hasWriter && hasPoller && hasConsumer {
		return []Finding{passFinding("internal/**/outbox/ (Writer/Poller/Consumer 구현 확인)")}
	}
	var missing []string
	if !hasWriter {
		missing = append(missing, "Writer")
	}
	if !hasPoller {
		missing = append(missing, "Poller")
	}
	if !hasConsumer {
		missing = append(missing, "Consumer")
	}
	return []Finding{failFinding("internal/**/outbox/", "outbox/ 디렉토리는 있으나 "+strings.Join(missing, ", ")+" 타입 선언을 찾을 수 없음")}
}

func checkTaskQueuePattern(root string) []Finding {
	hasTaskFile := false
	hasTaskDir := false
	walkErr := filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
		if err != nil {
			return nil
		}
		name := d.Name()
		pathSlash := filepath.ToSlash(path)
		if d.IsDir() {
			if name == "task-queue" {
				hasTaskDir = true
			}
			return nil
		}
		if strings.Contains(name, "task_queue") && !strings.Contains(pathSlash, "/task-queue/") {
			hasTaskFile = true
		}
		return nil
	})
	if walkErr != nil {
		return []Finding{failFinding(root, "디렉토리 탐색 실패: "+walkErr.Error())}
	}

	if !hasTaskFile {
		return []Finding{skipFinding("task-queue 패턴 없음")}
	}
	if hasTaskDir {
		return []Finding{passFinding("internal/**/task-queue/")}
	}
	return []Finding{failFinding("internal/**/task-queue/", "task 파일이 있으나 전용 task-queue/ 디렉토리 없음")}
}

package main

import (
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

// schedulerConstructRef — time.Ticker/time.NewTicker + robfig/cron 계열 스케줄링
// 구성요소 참조. cron 라이브러리 임포트 경로 자체("robfig/cron")도 함께 잡아,
// alias import로 심볼명을 바꿔도 놓치지 않는다.
var schedulerConstructRef = regexp.MustCompile(`\btime\.Ticker\b|\btime\.NewTicker\b|\brobfig/cron\b|\bcron\.New\b`)

// checkSchedulerInInfrastructureOnly — [16] scheduler-in-infrastructure-only:
// time.Ticker/time.NewTicker나 cron 라이브러리 사용은 internal/infrastructure/
// 에서만 허용한다(scheduling.md). internal/infrastructure/outbox/poller.go가 이미
// 정당하게 Ticker를 쓰고 있으므로(async Outbox 전환), 이 규칙은 domain/과
// application/만 스캔해 그 두 레이어로 스케줄링 원시 타입이 새어 나오는 것만 막는다.
func checkSchedulerInInfrastructureOnly(root string) RuleResult {
	result := RuleResult{Section: "scheduler-in-infrastructure-only"}
	found := false
	walkErr := filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() || !strings.HasSuffix(path, ".go") {
			return nil
		}
		if strings.HasSuffix(d.Name(), "_test.go") {
			return nil
		}
		slashPath := filepath.ToSlash(path)
		inDomain := strings.Contains(slashPath, "/internal/domain/")
		inApplication := strings.Contains(slashPath, "/internal/application/")
		if !inDomain && !inApplication {
			return nil
		}
		content, readErr := os.ReadFile(path)
		if readErr != nil {
			return nil
		}
		src := stripGoComments(string(content))
		rel, _ := filepath.Rel(root, path)
		found = true
		if schedulerConstructRef.MatchString(src) {
			result.Findings = append(result.Findings, failFinding(rel,
				"time.Ticker/cron 스케줄링 구성요소를 참조함 — 스케줄링은 internal/infrastructure/에서만 다뤄야 한다(docs/architecture/scheduling.md)"))
		} else {
			result.Findings = append(result.Findings, passFinding(rel))
		}
		return nil
	})
	if walkErr != nil {
		result.Findings = append(result.Findings, failFinding(root, "디렉토리 탐색 실패: "+walkErr.Error()))
	} else if !found {
		result.Findings = append(result.Findings, skipFinding("internal/domain/, internal/application/ 안에 .go 파일 없음"))
	}
	return result
}

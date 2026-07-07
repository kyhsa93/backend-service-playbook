// Go Harness — Go 프로젝트 구조·네이밍 규칙 검사
// Usage: go run . <projectRoot>
//
// 각 규칙은 별도 파일(file_naming.go, repository_placement.go, ...)에 구현되어
// 있고, 각각 RuleResult를 반환한다 — 이 파일은 규칙 목록을 정의하고 결과를
// 집계·출력하는 CLI 진입점 역할만 한다. 규칙별 회귀 테스트는 <rule>_test.go +
// testdata/<rule>/ fixture로 검증한다(README.md 참고).
package main

import (
	"fmt"
	"os"
)

var rules = []func(string) RuleResult{
	checkFileNaming,
	checkDirectoryStructure,
	checkRepositoryPlacement,
	checkHandlerPlacement,
	checkFilePlacement,
	checkSharedInfra,
	checkEventPlacement,
	checkOutboxDrainOrder,
}

func main() {
	root := "."
	if len(os.Args) > 1 {
		root = os.Args[1]
	}

	passCount, failCount := 0, 0
	for _, rule := range rules {
		result := rule(root)
		fmt.Printf("\n[%s]\n", result.Section)
		for _, f := range result.Findings {
			switch f.Kind {
			case Pass:
				passCount++
				fmt.Printf("  PASS  %s\n", f.Name)
			case Fail:
				failCount++
				fmt.Printf("  FAIL  %s — %s\n", f.Name, f.Reason)
			case Skip:
				fmt.Printf("  SKIP  %s\n", f.Name)
			}
		}
	}

	fmt.Printf("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n")
	if failCount == 0 {
		fmt.Printf("%d passed  PASS\n", passCount)
	} else {
		fmt.Printf("%d passed, %d failed  FAIL\n", passCount, failCount)
		os.Exit(1)
	}
}

package main

import (
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
)

// domainImportBC — import 경로에서 internal/domain/<bc>/... 의 <bc> 세그먼트를 뽑는다.
var domainImportBC = regexp.MustCompile(`/internal/domain/([^/]+)(?:/|$)`)

// checkCrossBCRepositoryInApplication — [14] no-cross-bc-repository-in-application:
// cross-domain-communication.md — "외부 BC의 Repository나 Service를 Application
// 레이어에서 직접 주입하지 않는다. Adapter를 통해서만 접근한다."
//
// internal/application/{command,query,event}/는 도메인별 하위 패키지로 나뉘지 않은
// flat 패키지이므로(CLAUDE.md의 payment_card_adapter.go 주석 참고), 파일 경로만으로는
// "이 파일이 어느 BC 소속인가"를 알 수 없다. 대신 그 파일이 실제로 import하는
// internal/domain/<bc>/ 패키지 집합으로 판단한다 — 정상적인 Application 파일은 자신이
// 조율하는 Aggregate가 속한 BC 하나의 domain 패키지만 import한다(다른 BC는 그 BC의
// infrastructure/acl 어댑터가 정의하는 자체 View 타입을 통해서만 접근하므로 domain
// import가 필요 없다). 한 파일이 서로 다른 BC의 domain 패키지를 2개 이상 import하면,
// Adapter를 거치지 않고 다른 BC의 Repository/Aggregate를 직접 참조하고 있다는 신호다.
func checkCrossBCRepositoryInApplication(root string) RuleResult {
	result := RuleResult{Section: "no-cross-bc-repository-in-application"}
	found := false
	walkErr := filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() || !strings.HasSuffix(path, ".go") {
			return nil
		}
		if strings.HasSuffix(d.Name(), "_test.go") {
			return nil
		}
		slashPath := filepath.ToSlash(path)
		if !strings.Contains(slashPath, "/internal/application/") {
			return nil
		}
		rel, _ := filepath.Rel(root, path)
		imports, parseErr := fileImportPaths(path)
		if parseErr != nil {
			found = true
			result.Findings = append(result.Findings, failFinding(rel, "Go 파일 파싱 실패: "+parseErr.Error()))
			return nil
		}

		bcSet := map[string]bool{}
		for _, imp := range imports {
			m := domainImportBC.FindStringSubmatch("/" + filepath.ToSlash(imp))
			if m != nil {
				bcSet[m[1]] = true
			}
		}
		if len(bcSet) == 0 {
			return nil // domain/ 패키지를 전혀 import하지 않는 파일(어댑터 정의 등)은 대상 아님
		}
		found = true
		if len(bcSet) > 1 {
			var bcs []string
			for bc := range bcSet {
				bcs = append(bcs, bc)
			}
			sort.Strings(bcs)
			result.Findings = append(result.Findings, failFinding(rel,
				"서로 다른 Bounded Context의 domain 패키지를 동시에 import함("+strings.Join(bcs, ", ")+") — 다른 BC는 infrastructure/acl Adapter를 통해서만 접근해야 한다(docs/architecture/cross-domain-communication.md)"))
		} else {
			result.Findings = append(result.Findings, passFinding(rel))
		}
		return nil
	})
	if walkErr != nil {
		result.Findings = append(result.Findings, failFinding(root, "디렉토리 탐색 실패: "+walkErr.Error()))
	} else if !found {
		result.Findings = append(result.Findings, skipFinding("internal/application/ 안에 domain/ import가 없음"))
	}
	return result
}

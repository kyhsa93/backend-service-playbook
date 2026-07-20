package main

import (
	"os"
	"path/filepath"
	"strings"
)

// forbiddenDomainImportSegments — domain/ 레이어가 import해서는 안 되는 상위 레이어
// 경로 세그먼트(layer-architecture.md: "Domain 레이어는 어떤 레이어에도 의존하지
// 않는다"). 특정 라이브러리 이름을 나열하는 블록리스트가 아니라 경로 기반이므로,
// application/infrastructure/interface 아래 어떤 새 패키지가 생기든(도메인 이름이
// 늘어나도) 자동으로 커버된다.
var forbiddenDomainImportSegments = []string{
	"/internal/application/",
	"/internal/infrastructure/",
	"/internal/interface/",
}

// checkDomainLayerIsolation — [10] domain-layer-isolation: internal/domain/**/*.go는
// internal/application|infrastructure|interface/의 어떤 패키지도 import할 수 없다
// (layer-architecture.md). go/parser로 import 선언만 정밀 파싱하므로(import_paths.go),
// 주석·문자열 리터럴 안에 저 경로들이 언급돼도 오탐하지 않는다.
func checkDomainLayerIsolation(root string) RuleResult {
	result := RuleResult{Section: "domain-layer-isolation"}
	found := false
	walkErr := filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() || !strings.HasSuffix(path, ".go") {
			return nil
		}
		if strings.HasSuffix(d.Name(), "_test.go") {
			return nil
		}
		slashPath := filepath.ToSlash(path)
		if !strings.Contains(slashPath, "/internal/domain/") {
			return nil
		}
		rel, _ := filepath.Rel(root, path)
		imports, parseErr := fileImportPaths(path)
		if parseErr != nil {
			found = true
			result.Findings = append(result.Findings, failFinding(rel, "Go 파일 파싱 실패: "+parseErr.Error()))
			return nil
		}
		found = true
		var violated []string
		for _, imp := range imports {
			slashImp := "/" + filepath.ToSlash(imp) + "/"
			for _, seg := range forbiddenDomainImportSegments {
				if strings.Contains(slashImp, seg) {
					violated = append(violated, imp)
					break
				}
			}
		}
		if len(violated) > 0 {
			result.Findings = append(result.Findings, failFinding(rel,
				"domain/ 레이어가 상위 레이어를 import함("+strings.Join(violated, ", ")+") — Domain은 어떤 레이어에도 의존하지 않는 프레임워크 무의존 코드여야 한다(layer-architecture.md)"))
		} else {
			result.Findings = append(result.Findings, passFinding(rel))
		}
		return nil
	})
	if walkErr != nil {
		result.Findings = append(result.Findings, failFinding(root, "디렉토리 탐색 실패: "+walkErr.Error()))
	} else if !found {
		result.Findings = append(result.Findings, skipFinding("internal/domain/ 없음"))
	}
	return result
}

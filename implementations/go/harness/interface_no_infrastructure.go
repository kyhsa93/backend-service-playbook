package main

import (
	"os"
	"path/filepath"
	"strings"
)

// checkInterfaceNoInfrastructure — [11] interface-no-infrastructure:
// internal/interface/**/*.go(HTTP 핸들러/라우터)는 internal/infrastructure/를
// 직접 import할 수 없다 — internal/application/(Command/Query 핸들러)에만
// 의존해야 한다(layer-architecture.md의 Interface → Application → Domain 의존
// 방향). infrastructure 구현체가 필요한 기술적 관심사(JWT 검증 등)는 사용하는
// 곳(interface/http/middleware 등) 근처에 작은 인터페이스를 선언해 구조적
// 타이핑으로 받는다(authentication.md가 서술하는 "인터페이스는 사용하는 레이어
// 근처에, 구현체는 infrastructure에" 원칙과 동일 — TokenIssuer/PasswordHasher가
// 이미 이 방식이다).
func checkInterfaceNoInfrastructure(root string) RuleResult {
	result := RuleResult{Section: "interface-no-infrastructure"}
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
			if strings.Contains("/"+filepath.ToSlash(imp)+"/", "/internal/infrastructure/") {
				violated = append(violated, imp)
			}
		}
		if len(violated) > 0 {
			result.Findings = append(result.Findings, failFinding(rel,
				"interface/ 레이어가 infrastructure/를 직접 import함("+strings.Join(violated, ", ")+") — HTTP 핸들러/라우터는 application/(Command/Query 핸들러)에만 의존해야 한다(layer-architecture.md)"))
		} else {
			result.Findings = append(result.Findings, passFinding(rel))
		}
		return nil
	})
	if walkErr != nil {
		result.Findings = append(result.Findings, failFinding(root, "디렉토리 탐색 실패: "+walkErr.Error()))
	} else if !found {
		result.Findings = append(result.Findings, skipFinding("internal/interface/ 없음"))
	}
	return result
}

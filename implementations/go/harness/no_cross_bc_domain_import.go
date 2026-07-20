package main

import (
	"os"
	"path/filepath"
	"strings"
)

// checkNoCrossBCDomainImport — no-cross-bc-domain-import: root docs/architecture/
// tactical-ddd.md, "다른 Aggregate는 ID 참조만 허용한다 (객체 참조 금지)" — 이 원칙은
// 같은 Bounded Context 안의 Aggregate 사이(no_cross_aggregate_reference.go가 payment/
// 안의 Payment↔Refund만 정밀 검사)뿐 아니라 서로 다른 BC 사이에도 그대로 적용된다.
// domain_layer_isolation.go(R1)는 domain/이 상위 레이어(application/infrastructure/
// interface)를 import하지 않는지만 보고, no_cross_aggregate_reference.go(R5)는 payment
// BC 내부만 본다 — 어떤 규칙도 `internal/domain/card`가 `internal/domain/payment`를
// 직접 import하는 것 같은, 서로 다른 BC의 domain 패키지 사이 import는 막지 않는다.
//
// internal/domain/<bc>/*.go 각 파일이 import하는 경로 중 internal/domain/<other-bc>
// (자기 자신이 아닌 다른 BC)가 있으면 위반이다. 정상적인 cross-BC 참조는 ID 문자열
// (`PaymentID string` 등)로만 이루어지므로 애초에 다른 BC의 domain 패키지를 import할
// 필요가 없다 — cross-domain-communication.md가 규정하는 Adapter(infrastructure/acl)
// 경유 방식도 domain 레이어가 아니라 application/infrastructure 레이어의 책임이다.
//
// internal/common(Money 등 공유 Value Object, ID 생성기)은 특정 BC 소속이 아니라
// 여러 BC가 공유하는 저수준 유틸리티 패키지이므로 domainImportBC 정규식(정확히
// internal/domain/<seg>/ 형태만 매칭)에 애초에 걸리지 않는다 — 이 규칙이 오탐하는
// "공유 타입" 케이스는 현재 이 저장소에 없다(각 domain/<bc> 파일은 자기 BC 밖의
// domain/ 패키지를 전혀 import하지 않는다 — 이 규칙은 향후 회귀를 잡는 가드다).
func checkNoCrossBCDomainImport(root string) RuleResult {
	result := RuleResult{Section: "no-cross-bc-domain-import"}
	domainDir := filepath.Join(root, "internal", "domain")
	if _, err := os.Stat(domainDir); os.IsNotExist(err) {
		result.Findings = append(result.Findings, skipFinding("internal/domain/ 없음"))
		return result
	}

	found := false
	walkErr := filepath.WalkDir(domainDir, func(path string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() || !strings.HasSuffix(path, ".go") || strings.HasSuffix(d.Name(), "_test.go") {
			return nil
		}
		slashPath := filepath.ToSlash(path)
		selfMatch := domainImportBC.FindStringSubmatch(slashPath[strings.Index(slashPath, "/internal/domain/"):])
		if selfMatch == nil {
			return nil
		}
		selfBC := selfMatch[1]
		rel, _ := filepath.Rel(root, path)

		imports, parseErr := fileImportPaths(path)
		if parseErr != nil {
			found = true
			result.Findings = append(result.Findings, failFinding(rel, "Go 파일 파싱 실패: "+parseErr.Error()))
			return nil
		}

		var otherBCs []string
		for _, imp := range imports {
			m := domainImportBC.FindStringSubmatch("/" + filepath.ToSlash(imp))
			if m != nil && m[1] != selfBC {
				otherBCs = append(otherBCs, m[1])
			}
		}
		found = true
		if len(otherBCs) > 0 {
			result.Findings = append(result.Findings, failFinding(rel,
				"다른 Bounded Context의 domain 패키지("+strings.Join(otherBCs, ", ")+")를 직접 import함 — Aggregate는 다른 Aggregate를 ID 문자열로만 참조해야 한다(docs/architecture/tactical-ddd.md)"))
		} else {
			result.Findings = append(result.Findings, passFinding(rel))
		}
		return nil
	})
	if walkErr != nil {
		result.Findings = append(result.Findings, failFinding(domainDir, "디렉토리 탐색 실패: "+walkErr.Error()))
	} else if !found {
		result.Findings = append(result.Findings, skipFinding("internal/domain/<bc>/ 아래 .go 파일 없음"))
	}
	return result
}

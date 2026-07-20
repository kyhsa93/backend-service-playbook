package main

import (
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

// checkRepositoryNaming — Repository/Query 인터페이스의 메서드 네이밍이 루트
// docs/architecture/repository-pattern.md의 find<Noun>s/save<Noun>/delete<Noun>
// 컨벤션을 따르는지 검사한다.
//
// domain/ 레이어의 Repository/Query interface 선언에 한정한다 — infrastructure/의
// 구현체는 private/internal 헬퍼 메서드를 자유롭게 가질 수 있어야 하므로 대상이 아니다
// (repository_placement.go가 같은 방식으로 domain/interface vs infrastructure/impl을
// 구분한다).
//
// 방식은 블록리스트다 — "find<Noun>s가 아닌 모든 것"을 잡는 문법을 만들면
// HasTransactionWithReference 같은 정상 메서드까지 오탐한다. 대신 실제로 반복 발생한
// 안티패턴(FindByID류, FindAll, Count*, 바레 Save/Delete, Update*)만 정밀 타겟팅한다.
// Update*는 repository-pattern.md가 명시적으로 금지한다 — 상태 변경은 Repository의
// 별도 update 메서드가 아니라 조회 후 Aggregate 도메인 메서드로 해야 한다.
var (
	// type XRepository interface { ... } / type XQuery interface { ... } — repository_placement.go의
	// repositoryInterfaceDecl과 동일한 관용구를 Query까지 확장.
	repoOrQueryInterfaceDecl = regexp.MustCompile(`(?m)^\s*type\s+(\w*(?:Repository|Query)\w*)\s+interface\s*\{`)
	// interface 본문 안의 메서드 시그니처 한 줄. embedded interface 이름만 있는 줄
	// (예: `Query`)은 뒤에 '('가 없으므로 매칭되지 않는다.
	interfaceMethodLine = regexp.MustCompile(`(?m)^\s*(\w+)\s*\(`)

	findByPattern = regexp.MustCompile(`^FindBy\w*$`)
	countPattern  = regexp.MustCompile(`^Count\w*$`)
	updatePattern = regexp.MustCompile(`^Update\w*$`)
)

// repositoryNamingViolation은 하나의 위반 메서드명에 대한 이유를 반환한다.
// 위반이 아니면 빈 문자열을 반환한다.
func repositoryNamingViolation(method string) string {
	switch {
	case findByPattern.MatchString(method):
		return "단건/조건부 조회 전용 메서드(" + method + ") — 조회는 항상 find<Noun>s 하나로 통일하고 필터로 좁혀야 한다(docs/architecture/repository-pattern.md)"
	case method == "FindAll":
		return "명사 없는 FindAll — find<Noun>s 형태로 대상을 명시해야 한다(docs/architecture/repository-pattern.md)"
	case countPattern.MatchString(method):
		return "별도 Count 메서드(" + method + ") — count는 find<Noun>s 결과에 함께 반환해야 하며 별도 메서드를 두지 않는다(docs/architecture/repository-pattern.md)"
	case updatePattern.MatchString(method):
		return "Repository에 수정(update) 메서드(" + method + ") — 상태 변경은 조회 후 Aggregate의 도메인 메서드로 수행하고 save<Noun>으로 저장해야 한다(docs/architecture/repository-pattern.md)"
	case method == "Save":
		return "명사 없는 Save — save<Noun> 형태로 대상을 명시해야 한다(docs/architecture/repository-pattern.md)"
	case method == "Delete":
		return "명사 없는 Delete — delete<Noun> 형태로 대상을 명시해야 한다(docs/architecture/repository-pattern.md)"
	default:
		return ""
	}
}

// extractInterfaceBody는 `interface {` 다음부터 컬럼 0에서 시작하는 다음 `}`까지를
// 본문으로 잘라낸다. 이 저장소의 Repository/Query interface 선언은 항상 최상위
// (들여쓰기 없음)이고 메서드 시그니처에는 중첩 `{}`가 없으므로(본문이 없는 interface
// 메서드 선언), 이 근사치로 충분하다(outbox_drain_order.go의 텍스트 검색 기반 근사와
// 동일한 트레이드오프).
func extractInterfaceBody(src string, bodyStart int) string {
	rest := src[bodyStart:]
	if idx := strings.Index(rest, "\n}"); idx != -1 {
		return rest[:idx]
	}
	return rest
}

// checkRepositoryNaming — domain/ Repository·Query interface의 메서드명이
// find<Noun>s/save<Noun>/delete<Noun> 컨벤션을 따르는지 검사한다.
func checkRepositoryNaming(root string) RuleResult {
	result := RuleResult{Section: "repository-naming"}
	found := false
	walkErr := filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() || !strings.HasSuffix(path, ".go") {
			return nil
		}
		if strings.HasSuffix(d.Name(), "_test.go") {
			return nil
		}
		slashPath := filepath.ToSlash(path)
		if !strings.Contains(slashPath, "/domain/") {
			return nil
		}
		content, readErr := os.ReadFile(path)
		if readErr != nil {
			return nil
		}
		rel, _ := filepath.Rel(root, path)
		src := string(content)

		matches := repoOrQueryInterfaceDecl.FindAllStringSubmatchIndex(src, -1)
		for _, m := range matches {
			ifaceName := src[m[2]:m[3]]
			bodyStart := m[1] // 매치 종료 위치 = "interface {" 바로 다음
			body := extractInterfaceBody(src, bodyStart)

			methodMatches := interfaceMethodLine.FindAllStringSubmatch(body, -1)
			violated := false
			for _, mm := range methodMatches {
				method := mm[1]
				if reason := repositoryNamingViolation(method); reason != "" {
					found = true
					violated = true
					result.Findings = append(result.Findings, failFinding(
						rel+" ("+ifaceName+"."+method+")", reason))
				}
			}
			if !violated {
				found = true
				result.Findings = append(result.Findings, passFinding(rel+" ("+ifaceName+")"))
			}
		}
		return nil
	})
	if walkErr != nil {
		result.Findings = append(result.Findings, failFinding(root, "디렉토리 탐색 실패: "+walkErr.Error()))
	} else if !found {
		result.Findings = append(result.Findings, skipFinding("domain/ Repository·Query interface 없음"))
	}
	return result
}

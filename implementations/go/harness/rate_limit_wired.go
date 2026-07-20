package main

import (
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

// checkRateLimitWired — rate-limit-wired: docs/architecture/rate-limiting.md가 요구하는
// rate limit 미들웨어가 단순히 정의만 되어 있는 게 아니라 실제로 라우터 조립
// 지점(router.go/main.go 등)에 등록돼 있는지 검사한다 — "만들어 놓기만 하고 아무도
// 호출하지 않는" 죽은 코드가 되는 회귀를 잡기 위함이다.
//
// 함수/메서드 이름에 "RateLimit"이 들어간 최상위(또는 리시버 있는) 선언을 찾아
// 정의 위치를 먼저 특정한 뒤, 그 정의 파일과 테스트 파일을 제외한 나머지 소스 전체에서
// 같은 심볼을 호출하는 참조(`RateLimit(` 또는 `middleware.RateLimit(`처럼 패키지
// 접두사가 붙은 형태 포함)가 있는지 찾는다 — 정의 파일 자체에는 당연히 `func
// RateLimit(...)` 선언 자체가 "RateLimit("과 매칭되므로 그 파일을 검색 대상에서 빼야
// 자기 자신의 선언을 "호출"로 오인하지 않는다. 테스트 파일에서만 참조되는 경우는
// "실제 라우터에 배선됨"으로 보지 않는다(이 규칙이 확인하려는 것은 프로덕션 배선이다).
var rateLimitFuncDecl = regexp.MustCompile(`(?m)^func\s+(?:\(\s*\w+\s+\*?\w+\s*\)\s+)?(\w*RateLimit\w*)\s*\(`)

func checkRateLimitWired(root string) RuleResult {
	result := RuleResult{Section: "rate-limit-wired"}

	type decl struct {
		file string
		name string
	}
	var decls []decl
	walkErr := filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() || !strings.HasSuffix(path, ".go") {
			return nil
		}
		if strings.HasSuffix(d.Name(), "_test.go") {
			return nil
		}
		// middleware/ 밖의 함수(예: internal/config/rate_limit.go의 LoadRateLimitConfig)까지
		// 이름에 "RateLimit"이 들어간다는 이유만으로 미들웨어 후보로 잡으면 안 된다 —
		// 실제 HTTP 미들웨어 생성자는 interface/http/middleware/ 아래에 있어야 한다
		// (layer-architecture.md, rate-limiting.md).
		if !strings.Contains(filepath.ToSlash(path), "/middleware/") {
			return nil
		}
		content, readErr := os.ReadFile(path)
		if readErr != nil {
			return nil
		}
		src := stripGoComments(string(content))
		for _, m := range rateLimitFuncDecl.FindAllStringSubmatch(src, -1) {
			decls = append(decls, decl{file: path, name: m[1]})
		}
		return nil
	})
	if walkErr != nil {
		result.Findings = append(result.Findings, failFinding(root, "디렉토리 탐색 실패: "+walkErr.Error()))
		return result
	}
	if len(decls) == 0 {
		result.Findings = append(result.Findings, skipFinding("RateLimit 미들웨어 함수/메서드 없음 — docs/architecture/rate-limiting.md"))
		return result
	}

	definingFiles := make(map[string]bool, len(decls))
	for _, dl := range decls {
		definingFiles[dl.file] = true
	}

	for _, dl := range decls {
		callPattern := regexp.MustCompile(`(?:\w+\.)?` + regexp.QuoteMeta(dl.name) + `\s*\(`)
		wired := false
		walkErr := filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
			if err != nil || d.IsDir() || wired || !strings.HasSuffix(path, ".go") {
				return nil
			}
			if strings.HasSuffix(d.Name(), "_test.go") || definingFiles[path] {
				return nil
			}
			content, readErr := os.ReadFile(path)
			if readErr != nil {
				return nil
			}
			if callPattern.MatchString(stripGoComments(string(content))) {
				wired = true
			}
			return nil
		})
		if walkErr != nil {
			result.Findings = append(result.Findings, failFinding(root, "디렉토리 탐색 실패: "+walkErr.Error()))
			continue
		}

		rel, _ := filepath.Rel(root, dl.file)
		if wired {
			result.Findings = append(result.Findings, passFinding(rel+" ("+dl.name+" — 라우터에 배선됨)"))
		} else {
			result.Findings = append(result.Findings, failFinding(rel+" ("+dl.name+")",
				"rate limit 미들웨어가 정의만 되어 있고 어디에서도 호출되지 않음(테스트 파일 제외) — "+
					"router.go/main.go 같은 조립 지점에 실제로 등록해야 한다(docs/architecture/rate-limiting.md)"))
		}
	}

	return result
}

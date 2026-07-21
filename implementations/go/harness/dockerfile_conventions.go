package main

import (
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
)

var (
	fromLine        = regexp.MustCompile(`(?mi)^\s*FROM\s+\S+`)
	healthcheckLine = regexp.MustCompile(`(?mi)^\s*HEALTHCHECK\b`)
	userLine        = regexp.MustCompile(`(?mi)^\s*USER\s+\S+`)
)

// dockerignoreExcludes — container.md/local-dev.md가 요구하는 최소 제외 대상.
// 소스 저장소(.git), 비밀값(.env*)은 이미지에 절대 들어가면 안 되는 대표적인 항목이라
// 그 둘을 "reasonable excludes"의 최소 기준으로 삼는다.
var dockerignoreExcludes = []string{".git", ".env"}

// checkDockerfileConventions — [18] dockerfile-conventions: examples/Dockerfile 한
// 파일과 옆의 .dockerignore를 직접 읽어 검사한다(container.md). 이 규칙은 다른
// 규칙들과 달리 디렉토리를 재귀 탐색하지 않는다 — Go 소스가 아니라 지정된 두 파일
// (<root>/Dockerfile, <root>/.dockerignore)만 대상으로 하는 단일 파일 검사이기
// 때문이다.
//
// (a) 멀티스테이지 빌드 — FROM 라인이 2개 이상
// (b) HEALTHCHECK 인스트럭션 존재
// (c) .dockerignore가 존재하고 .git·.env* 같은 합리적인 제외 대상을 포함
func checkDockerfileConventions(root string) RuleResult {
	result := RuleResult{Section: "dockerfile-conventions"}
	dockerfilePath := filepath.Join(root, "Dockerfile")
	content, err := os.ReadFile(dockerfilePath)
	if os.IsNotExist(err) {
		result.Findings = append(result.Findings, skipFinding("Dockerfile 없음"))
		return result
	}
	if err != nil {
		result.Findings = append(result.Findings, failFinding("Dockerfile", "읽기 실패: "+err.Error()))
		return result
	}
	src := string(content)

	fromCount := len(fromLine.FindAllString(src, -1))
	if fromCount >= 2 {
		result.Findings = append(result.Findings, passFinding("Dockerfile (멀티스테이지, FROM "+strconv.Itoa(fromCount)+"개)"))
	} else {
		result.Findings = append(result.Findings, failFinding("Dockerfile", "멀티스테이지 빌드가 아님(FROM "+strconv.Itoa(fromCount)+"개) — 빌드 스테이지와 런타임 스테이지를 분리해야 한다(docs/architecture/container.md)"))
	}

	if healthcheckLine.MatchString(src) {
		result.Findings = append(result.Findings, passFinding("Dockerfile (HEALTHCHECK 있음)"))
	} else {
		result.Findings = append(result.Findings, failFinding("Dockerfile", "HEALTHCHECK 인스트럭션이 없음(docs/architecture/container.md)"))
	}

	if userLine.MatchString(src) {
		result.Findings = append(result.Findings, passFinding("Dockerfile (non-root USER 있음)"))
	} else {
		result.Findings = append(result.Findings, failFinding("Dockerfile", "USER 지시문이 없음 — 컨테이너가 root로 실행됨(docs/architecture/container.md)"))
	}

	dockerignorePath := filepath.Join(root, ".dockerignore")
	diContent, diErr := os.ReadFile(dockerignorePath)
	switch {
	case os.IsNotExist(diErr):
		result.Findings = append(result.Findings, failFinding(".dockerignore", ".dockerignore 파일이 없음 — 빌드 컨텍스트에 불필요한 파일(.git, 비밀값 등)이 포함될 수 있다(docs/architecture/container.md)"))
	case diErr != nil:
		result.Findings = append(result.Findings, failFinding(".dockerignore", "읽기 실패: "+diErr.Error()))
	default:
		diSrc := string(diContent)
		var missing []string
		for _, exclude := range dockerignoreExcludes {
			if !strings.Contains(diSrc, exclude) {
				missing = append(missing, exclude)
			}
		}
		if len(missing) == 0 {
			result.Findings = append(result.Findings, passFinding(".dockerignore (.git/.env 제외 확인)"))
		} else {
			result.Findings = append(result.Findings, failFinding(".dockerignore", "합리적인 제외 대상이 빠짐("+strings.Join(missing, ", ")+")"))
		}
	}

	return result
}

package main

import (
	"go/parser"
	"go/token"
	"strconv"
)

// fileImportPaths는 Go 소스 파일의 import 선언에 나열된 경로 문자열(따옴표 제거)만
// 뽑아 반환한다. 여러 규칙(domain-layer-isolation, interface-no-infrastructure,
// no-cross-bc-repository-in-application, no-logging-in-domain)이 "이 파일이 특정
// 레이어/패키지를 import하는가"를 판단해야 하는데, repository_naming.go 등 다른
// 규칙처럼 정규식으로 import 블록을 근사하면 주석이나 문자열 리터럴 안의 경로 비슷한
// 텍스트에 오탐할 여지가 남는다(outbox_drain_order.go의 stripGoComments가 바로 이
// 문제를 회피하려고 도입된 것). import 선언은 go/parser의 parser.ImportsOnly 모드로
// 패키지 절+import만 파싱하면 되므로(함수 본문은 파싱하지 않아 문법 오류에도 안전하고
// 빠르다), 여기서는 정규식 대신 표준 라이브러리 파서를 그대로 쓴다 — 결과적으로 더
// 간단하고 완전히 정밀하다.
func fileImportPaths(path string) ([]string, error) {
	fset := token.NewFileSet()
	f, err := parser.ParseFile(fset, path, nil, parser.ImportsOnly)
	if err != nil {
		return nil, err
	}
	paths := make([]string, 0, len(f.Imports))
	for _, imp := range f.Imports {
		unquoted, err := strconv.Unquote(imp.Path.Value)
		if err != nil {
			continue
		}
		paths = append(paths, unquoted)
	}
	return paths, nil
}

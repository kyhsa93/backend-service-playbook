package main

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// Go에는 NestJS의 @Module({ providers: [...] }) 같은 DI 컨테이너가 없다 — main.go의
// 생성자 체이닝이 그 역할을 대신한다(module-pattern.md). Card BC도 별도 Relay/Consumer
// 없이 main.go가 조립하는 단일 공유 map[string]outbox.Handler에 자기 이벤트를 등록하고,
// 이 map을 outbox.Poller(발행)/outbox.Consumer(수신·실행)가 함께 쓴다
// (internal/infrastructure/outbox/poller.go, consumer.go). 그래서 이 생성기는 nestjs처럼
// app-module.ts 하나만 고치는 게 아니라 main.go(핸들러 맵 + 저장소 조립)와 router.go
// (HTTP 라우팅 + Handler 조립) 두 파일을 함께, 그리고 기존 코드의 실제 앵커 문자열을
// 기준으로 고친다. Command Handler는 이 handlers map을 전혀 참조하지 않는다(동기
// 드레인 금지, domain-events.md) — 저장 후 곧바로 반환한다.

// findMatchingClose는 src[openIdx]에 있는 여는 문자(open)와 짝이 맞는 닫는 문자(close)의
// 인덱스를 찾는다. 문자열 리터럴/주석은 구분하지 않는 단순 깊이 계산이지만, 이 저장소의
// main.go/router.go처럼 문자열 리터럴 안에 중괄호/괄호가 없는 코드에는 충분하다.
func findMatchingClose(src string, openIdx int, open, closeCh byte) (int, error) {
	if openIdx < 0 || openIdx >= len(src) || src[openIdx] != open {
		return -1, fmt.Errorf("openIdx가 여는 문자(%q)를 가리키지 않음", string(open))
	}
	depth := 0
	for i := openIdx; i < len(src); i++ {
		switch src[i] {
		case open:
			depth++
		case closeCh:
			depth--
			if depth == 0 {
				return i, nil
			}
		}
	}
	return -1, fmt.Errorf("짝이 맞는 닫는 문자(%q)를 찾지 못함", string(closeCh))
}

// insertAfterLine은 anchor 문자열이 포함된 줄의 바로 다음 줄에 insertion을 삽입한다.
func insertAfterLine(src, anchor, insertion string) (string, error) {
	idx := strings.Index(src, anchor)
	if idx < 0 {
		return "", fmt.Errorf("앵커를 찾지 못함: %q", anchor)
	}
	nl := strings.Index(src[idx:], "\n")
	if nl < 0 {
		return "", fmt.Errorf("앵커가 있는 줄에 개행이 없음: %q", anchor)
	}
	pos := idx + nl + 1
	return src[:pos] + insertion + src[pos:], nil
}

// insertBeforeMarker는 marker 바로 앞에 insertion을 끼워 넣는다.
func insertBeforeMarker(src, marker, insertion string) (string, error) {
	idx := strings.Index(src, marker)
	if idx < 0 {
		return "", fmt.Errorf("마커를 찾지 못함: %q", marker)
	}
	return src[:idx] + insertion + src[idx:], nil
}

// insertBeforeMatchingClose는 openMarker(끝이 여는 문자 open으로 끝나는 문자열)를 찾고,
// 그 여는 문자와 짝이 맞는 닫는 문자 바로 앞에 insertion을 끼워 넣는다. map 리터럴이나
// 함수 호출의 인자 목록처럼 반복 실행(여러 도메인 추가)에도 안정적인 위치를 잡기 위해
// 문자열 직접 매칭이 아니라 괄호 깊이 계산을 쓴다.
func insertBeforeMatchingClose(src, openMarker string, open, closeCh byte, insertion string) (string, error) {
	markerIdx := strings.Index(src, openMarker)
	if markerIdx < 0 {
		return "", fmt.Errorf("openMarker를 찾지 못함: %q", openMarker)
	}
	openIdx := markerIdx + len(openMarker) - 1
	closeIdx, err := findMatchingClose(src, openIdx, open, closeCh)
	if err != nil {
		return "", fmt.Errorf("%q 이후 매칭 실패: %w", openMarker, err)
	}
	return src[:closeIdx] + insertion + src[closeIdx:], nil
}

// WireResult는 wiring 단계가 실제로 고친 파일들, 이미 적용돼 건너뛴 상태를 보고한다.
type WireResult struct {
	ChangedFiles []string
	AlreadyWired bool
}

// dependencyAssemblySites는 targetRoot 아래에서 main.go와 "동일한 모양의" 의존성
// 조립 코드를 담은 모든 파일을 찾는다. cmd/server/main.go가 유일한 대상이 아니다 —
// 이 저장소의 test/account_e2e_test.go처럼 e2e 테스트가 main.go를 거치지 않고
// 저장소/Relay/httphandler.NewRouter(...)를 자기 안에서 직접 다시 조립하는 경우가
// 있고, 이런 파일을 빠뜨리면 go vet(테스트 파일도 타입체크한다)에서만 드러나는
// "not enough arguments in call to NewRouter" 컴파일 에러가 남는다 — 실제로 이
// 생성기를 만들며 발견한 버그다. 두 앵커(Card 저장소 조립 줄, httphandler.NewRouter
// 호출)를 모두 포함하는 .go 파일이면 이 조립 사이트로 간주한다.
func dependencyAssemblySites(targetRoot string) ([]string, error) {
	var sites []string
	err := filepath.Walk(targetRoot, func(path string, info os.FileInfo, err error) error {
		if err != nil || info.IsDir() || !strings.HasSuffix(path, ".go") {
			return nil
		}
		data, readErr := os.ReadFile(path)
		if readErr != nil {
			return nil
		}
		src := string(data)
		if strings.Contains(src, "persistence.NewCardRepository(db)") && strings.Contains(src, "httphandler.NewRouter(") {
			sites = append(sites, path)
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	return sites, nil
}

// WireMainAndRouter는 router.go 및 모든 의존성 조립 사이트(main.go + 이를 다시
// 조립하는 e2e 테스트 등)에 새 도메인의 저장소/핸들러/라우트를 프로그램적으로
// 삽입한다. 이미 등록돼 있으면 아무것도 하지 않고 AlreadyWired=true를 반환한다.
func WireMainAndRouter(targetRoot string, n Names) (WireResult, error) {
	mainPath := filepath.Join(targetRoot, "cmd", "server", "main.go")
	routerPath := filepath.Join(targetRoot, "internal", "interface", "http", "router.go")

	mainSrc, err := os.ReadFile(mainPath)
	if err != nil {
		return WireResult{}, fmt.Errorf("main.go 읽기 실패: %w", err)
	}

	repoCtorName := fmt.Sprintf("persistence.New%sRepository", n.Domain)
	if strings.Contains(string(mainSrc), repoCtorName) {
		return WireResult{AlreadyWired: true}, nil
	}

	sites, err := dependencyAssemblySites(targetRoot)
	if err != nil {
		return WireResult{}, fmt.Errorf("의존성 조립 사이트 탐색 실패: %w", err)
	}
	if len(sites) == 0 {
		return WireResult{}, fmt.Errorf("의존성 조립 사이트를 찾지 못함(persistence.NewCardRepository(db) + httphandler.NewRouter( 둘 다 포함하는 .go 파일 없음)")
	}

	var changed []string
	for _, path := range sites {
		src, readErr := os.ReadFile(path)
		if readErr != nil {
			return WireResult{}, fmt.Errorf("%s 읽기 실패: %w", path, readErr)
		}
		newSrc, wireErr := wireDependencyAssembly(string(src), n)
		if wireErr != nil {
			return WireResult{}, fmt.Errorf("%s wiring 실패: %w", path, wireErr)
		}
		if err := os.WriteFile(path, []byte(newSrc), 0o644); err != nil {
			return WireResult{}, fmt.Errorf("%s 쓰기 실패: %w", path, err)
		}
		changed = append(changed, path)
	}

	routerSrc, err := os.ReadFile(routerPath)
	if err != nil {
		return WireResult{}, fmt.Errorf("router.go 읽기 실패: %w", err)
	}
	newRouter, err := wireRouter(string(routerSrc), n)
	if err != nil {
		return WireResult{}, fmt.Errorf("router.go wiring 실패: %w", err)
	}
	if err := os.WriteFile(routerPath, []byte(newRouter), 0o644); err != nil {
		return WireResult{}, fmt.Errorf("router.go 쓰기 실패: %w", err)
	}
	changed = append(changed, routerPath)

	return WireResult{ChangedFiles: changed}, nil
}

// wireDependencyAssembly는 main.go와 동일한 모양(Card 저장소 조립 -> 공유
// map[string]outbox.Handler -> httphandler.NewRouter(...) 호출)을 갖는 파일 하나에
// 새 도메인을 엮는다.
func wireDependencyAssembly(src string, n Names) (string, error) {
	// 1. 저장소 조립 — cardRepo 생성 줄 바로 다음에 추가(Card는 항상 존재하는 안정적 앵커).
	repoLine := fmt.Sprintf("\t%sRepo := persistence.New%sRepository(db)\n", n.DomainCamel, n.Domain)
	src, err := insertAfterLine(src, "cardRepo := persistence.NewCardRepository(db)\n", repoLine)
	if err != nil {
		return "", err
	}

	// 2. 공유 handlers map(outbox.Poller/outbox.Consumer가 함께 쓴다)에 이벤트 핸들러
	// 등록 — map 리터럴의 닫는 중괄호 바로 앞에 끼워 넣는다(이미 등록된 도메인이 몇 개든
	// 상관없이 항상 마지막에 추가됨).
	entry := fmt.Sprintf("\t\t\"%sCancelled\": event.New%sCancelledEventHandler().Handle,\n", n.Domain, n.Domain)
	src, err = insertBeforeMatchingClose(src, "map[string]outbox.Handler{", '{', '}', entry)
	if err != nil {
		return "", err
	}

	// 3. NewRouter 호출 인자 목록 끝에 새 저장소 추가.
	arg := fmt.Sprintf(", %sRepo", n.DomainCamel)
	src, err = insertBeforeMatchingClose(src, "httphandler.NewRouter(", '(', ')', arg)
	if err != nil {
		return "", err
	}

	return src, nil
}

func wireRouter(src string, n Names) (string, error) {
	// 1. 새 도메인 패키지 import 추가 — domain/card import 다음 줄(Card는 항상 존재).
	importLine := fmt.Sprintf("\t\"%s/internal/domain/%s\"\n", n.ModulePath, n.DomainLower)
	cardImportAnchor := fmt.Sprintf("\"%s/internal/domain/card\"\n", n.ModulePath)
	src, err := insertAfterLine(src, cardImportAnchor, importLine)
	if err != nil {
		return "", err
	}

	// 2. NewRouter 함수 시그니처에 새 저장소 파라미터 추가 — 파라미터 목록이 끝나는
	// 지점(반환 타입 시작 직전)은 반복 실행에도 항상 동일한 문자열로 남는다.
	param := fmt.Sprintf(", %sRepo %s.Repository", n.DomainCamel, n.DomainLower)
	src, err = insertBeforeMarker(src, ") (http.Handler, *HealthHandler) {", param)
	if err != nil {
		return "", err
	}

	// 3. Command/Query Handler + HTTP Handler 조립 — cardHTTP 조립 다음에 추가.
	construction := fmt.Sprintf(
		"\n\t// %s BC — 스캐폴딩 생성기(scripts/create-domain)가 생성.\n"+
			"\tcreate%sHandler := command.NewCreate%sHandler(%sRepo)\n"+
			"\tcancel%sHandler := command.NewCancel%sHandler(%sRepo)\n"+
			"\tget%sHandler := query.NewGet%sHandler(%sRepo)\n"+
			"\t%sHTTP := New%sHandler(create%sHandler, cancel%sHandler, get%sHandler)\n",
		n.Domain,
		n.Domain, n.Domain, n.DomainCamel,
		n.Domain, n.Domain, n.DomainCamel,
		n.Domain, n.Domain, n.DomainCamel,
		n.DomainCamel, n.Domain, n.Domain, n.Domain, n.Domain,
	)
	src, err = insertAfterLine(src, "cardHTTP := NewCardHandler(issueCardHandler, getCardHandler)\n", construction)
	if err != nil {
		return "", err
	}

	// 4. protected mux에 라우트 등록 — Card의 GetCard 라우트 다음에 추가.
	routes := fmt.Sprintf(
		"\tprotected.HandleFunc(\"POST /%s\", %sHTTP.Create%s)\n"+
			"\tprotected.HandleFunc(\"POST /%s/{id}/cancel\", %sHTTP.Cancel%s)\n"+
			"\tprotected.HandleFunc(\"GET /%s/{id}\", %sHTTP.Get%s)\n",
		n.DomainsLower, n.DomainCamel, n.Domain,
		n.DomainsLower, n.DomainCamel, n.Domain,
		n.DomainsLower, n.DomainCamel, n.Domain,
	)
	src, err = insertAfterLine(src, `protected.HandleFunc("GET /cards/{cardId}", cardHTTP.GetCard)`+"\n", routes)
	if err != nil {
		return "", err
	}

	// 5. rate limit 대상(limited mux)에도 새 경로를 등록 — Card의 등록 다음에 추가.
	limited := fmt.Sprintf(
		"\tlimited.Handle(\"/%s\", middleware.RequireAuth(jwtService)(protected))\n"+
			"\tlimited.Handle(\"/%s/\", middleware.RequireAuth(jwtService)(protected))\n",
		n.DomainsLower, n.DomainsLower,
	)
	src, err = insertAfterLine(src, `limited.Handle("/cards/", middleware.RequireAuth(jwtService)(protected))`+"\n", limited)
	if err != nil {
		return "", err
	}

	return src, nil
}

// PrintWiringSnippet은 --wire 없이 실행했을 때 수동으로 적용할 내용을 안내한다.
func PrintWiringSnippet(n Names) {
	fmt.Println()
	fmt.Println("--- main.go / router.go에 수동으로 추가할 내용 (--wire를 주지 않았으므로 자동 적용 안 됨) ---")
	fmt.Println()
	fmt.Printf("cmd/server/main.go:\n")
	fmt.Printf("  %sRepo := persistence.New%sRepository(db)\n", n.DomainCamel, n.Domain)
	fmt.Printf("  공유 outboxHandlers map(map[string]outbox.Handler)에 추가: \"%sCancelled\": event.New%sCancelledEventHandler().Handle,\n", n.Domain, n.Domain)
	fmt.Printf("  httphandler.NewRouter(... , %sRepo)\n", n.DomainCamel)
	fmt.Println()
	fmt.Printf("internal/interface/http/router.go:\n")
	fmt.Printf("  NewRouter 파라미터에 추가: %sRepo %s.Repository\n", n.DomainCamel, n.DomainLower)
	fmt.Printf("  create/cancel/get %s 핸들러 조립 + %sHTTP := New%sHandler(...)\n", n.Domain, n.DomainCamel, n.Domain)
	fmt.Printf("  protected/limited mux에 /%s 라우트 등록\n", n.DomainsLower)
	fmt.Println()
}

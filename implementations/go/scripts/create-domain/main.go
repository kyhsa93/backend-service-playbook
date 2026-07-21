// 새 도메인 스캐폴딩 생성기 — docs/reference.md의 "실전 구현 템플릿"(Order 예시)을
// 실제 코드로 만든 뒤, 도메인 이름만 파라미터로 뽑아 재사용 가능하게 일반화한 것이다.
// Aggregate(단일 Status 필드) + CQRS Command/Query Handler + 도메인 이벤트 1종 +
// Repository(도메인 인터페이스 + infrastructure 구현체) + HTTP Handler + DTO까지
// 한 번에 생성한다.
//
// 이 디렉토리는 examples/harness와 마찬가지로 독립된 Go module이다 — 반드시 이 디렉토리
// 안에서 실행한다(implementations/go/에는 이들을 묶는 go.mod/go.work가 없다).
//
// 사용법 (scripts/create-domain/ 안에서):
//
//	go run . <PascalCaseDomainName> [--out <targetRoot>] [--wire]
//
// 예:
//
//	go run . Coupon
//	  -> examples/internal/... 아래에 생성(스크립트 기본 대상), main.go/router.go는 안 건드림
//	go run . Coupon --out /tmp/scratch-app --wire
//	  -> 지정한 루트 아래 생성 + cmd/server/main.go, internal/interface/http/router.go에
//	     저장소/핸들러/라우트까지 자동 삽입
//
// --wire를 주지 않으면 main.go/router.go는 건드리지 않고, 붙여넣을 내용을 콘솔에
// 안내만 한다 — 기존 프로젝트의 중앙 조립 파일을 스크립트가 임의로 고치는 걸 원치 않을
// 수 있어 기본값은 안전한 쪽(수동 적용)으로 둔다(nestjs의 create-domain.js와 동일한 기본값 철학).
//
// Go는 도메인마다 전용 Relay/Consumer를 두지 않는다 — main.go가 조립하는 단일 공유
// map[string]outbox.Handler를 outbox.Poller(발행)/outbox.Consumer(수신·실행)가 함께
// 쓰므로, main.go의 이 handlers map에 새 도메인 항목을 추가하는 것이 이 생성기의 핵심
// wiring 대상이다(자세한 설계는 wiring.go 주석 참고). Command Handler는 이 map을
// 전혀 참조하지 않는다 — 저장 후 곧바로 반환한다(동기 드레인 금지, domain-events.md).
package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"strconv"
	"strings"
)

func usage() {
	fmt.Fprintln(os.Stderr, "사용법 (scripts/create-domain/ 안에서): go run . <PascalCaseDomainName> [--out <targetRoot>] [--wire]")
}

// defaultTargetRoot는 이 스크립트 파일 자신의 위치를 기준으로 ../../examples를 가리킨다 —
// go run으로 실행해도 runtime.Caller(0)은 컴파일 시점의 소스 경로를 그대로 반환한다.
func defaultTargetRoot() string {
	_, thisFile, _, _ := runtime.Caller(0)
	scriptDir := filepath.Dir(thisFile)
	return filepath.Join(scriptDir, "..", "..", "examples")
}

// readModulePath는 targetRoot/go.mod의 첫 "module " 줄에서 import 경로 prefix를 읽는다.
func readModulePath(targetRoot string) (string, error) {
	data, err := os.ReadFile(filepath.Join(targetRoot, "go.mod"))
	if err != nil {
		return "", fmt.Errorf("go.mod 읽기 실패: %w", err)
	}
	for _, line := range strings.Split(string(data), "\n") {
		line = strings.TrimSpace(line)
		if strings.HasPrefix(line, "module ") {
			return strings.TrimSpace(strings.TrimPrefix(line, "module ")), nil
		}
	}
	return "", fmt.Errorf("go.mod에서 module 선언을 찾지 못함")
}

var migrationFileRe = regexp.MustCompile(`^(\d+)_`)

// nextMigrationSeq는 targetRoot/migrations/ 안의 0001_xxx.sql 같은 파일명에서
// 가장 큰 번호 + 1을 반환한다. migrations/ 디렉토리가 없으면 1을 반환한다.
func nextMigrationSeq(targetRoot string) int {
	entries, err := os.ReadDir(filepath.Join(targetRoot, "migrations"))
	if err != nil {
		return 1
	}
	max := 0
	for _, e := range entries {
		m := migrationFileRe.FindStringSubmatch(e.Name())
		if m == nil {
			continue
		}
		if v, err := strconv.Atoi(m[1]); err == nil && v > max {
			max = v
		}
	}
	return max + 1
}

func writeFile(path, content string) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	return os.WriteFile(path, []byte(content), 0o644)
}

// gofmtFiles는 생성/수정한 .go 파일들에 gofmt -w를 돌려 임포트 정렬·포맷을 맞춘다.
// gofmt 바이너리가 없으면 조용히 건너뛴다(빌드/vet/harness 결과에는 영향 없음 — 포맷
// 문제는 golangci-lint의 gofmt formatter가 별도로 잡아준다).
func gofmtFiles(paths []string) {
	gofmtBin, err := exec.LookPath("gofmt")
	if err != nil {
		fmt.Fprintf(os.Stderr, "경고: gofmt를 찾지 못해 포맷팅을 건너뜁니다: %v\n", err)
		return
	}
	for _, p := range paths {
		cmd := exec.Command(gofmtBin, "-w", p)
		if out, err := cmd.CombinedOutput(); err != nil {
			fmt.Fprintf(os.Stderr, "경고: gofmt -w %s 실패: %v\n%s\n", p, err, out)
		}
	}
}

func main() {
	args := os.Args[1:]
	if len(args) == 0 || strings.HasPrefix(args[0], "--") {
		usage()
		os.Exit(1)
	}
	rawDomainName := args[0]

	targetRoot := defaultTargetRoot()
	shouldWire := false
	for i := 1; i < len(args); i++ {
		switch args[i] {
		case "--out":
			if i+1 >= len(args) {
				usage()
				os.Exit(1)
			}
			targetRoot = args[i+1]
			i++
		case "--wire":
			shouldWire = true
		default:
			fmt.Fprintf(os.Stderr, "알 수 없는 인자: %s\n", args[i])
			usage()
			os.Exit(1)
		}
	}

	absTargetRoot, err := filepath.Abs(targetRoot)
	if err != nil {
		fmt.Fprintf(os.Stderr, "대상 경로 처리 실패: %v\n", err)
		os.Exit(1)
	}

	modulePath, err := readModulePath(absTargetRoot)
	if err != nil {
		fmt.Fprintf(os.Stderr, "%v\n", err)
		os.Exit(1)
	}

	n := BuildNames(rawDomainName, modulePath)
	migrationSeq := nextMigrationSeq(absTargetRoot)
	files := GenerateFiles(n, migrationSeq)

	var writtenGoFiles []string
	for relPath, content := range files {
		fullPath := filepath.Join(absTargetRoot, relPath)
		if err := writeFile(fullPath, content); err != nil {
			fmt.Fprintf(os.Stderr, "파일 쓰기 실패(%s): %v\n", relPath, err)
			os.Exit(1)
		}
		if strings.HasSuffix(fullPath, ".go") {
			writtenGoFiles = append(writtenGoFiles, fullPath)
		}
	}

	fmt.Printf("%s 도메인 생성 완료: %s 아래 (%d개 파일)\n", n.Domain, absTargetRoot, len(files))
	fmt.Printf("REST 경로: /%s (POST 생성, GET/{id} 조회, POST /{id}/cancel 취소)\n", n.DomainsLower)
	fmt.Println()
	fmt.Println("참고: 나이브 복수형 규칙(+s / +es / y→ies)을 썼습니다 — 불규칙 복수형 도메인이면")
	fmt.Printf("  %s(테이블/경로명) 등을 수동으로 다듬어야 할 수 있습니다.\n", n.DomainsLower)

	if shouldWire {
		result, err := WireMainAndRouter(absTargetRoot, n)
		if err != nil {
			fmt.Fprintf(os.Stderr, "wiring 실패: %v\n", err)
			os.Exit(1)
		}
		switch {
		case result.AlreadyWired:
			fmt.Println("이미 main.go에 등록돼 있어 wiring을 건너뜁니다.")
		default:
			fmt.Printf("저장소·핸들러·라우트 등록 완료 — 수정된 파일 %d개:\n", len(result.ChangedFiles))
			for _, f := range result.ChangedFiles {
				fmt.Printf("  %s\n", f)
			}
			writtenGoFiles = append(writtenGoFiles, result.ChangedFiles...)
		}
	} else {
		PrintWiringSnippet(n)
	}

	gofmtFiles(writtenGoFiles)

	fmt.Println("다음: go build ./..., go vet ./... 로 컴파일을 확인하고, bash harness.sh <projectRoot>로 검증하세요.")
}

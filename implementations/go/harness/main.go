// Go Harness — Go 프로젝트 구조·네이밍 규칙 검사
// Usage: go run . <projectRoot>
package main

import (
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

var (
	passCount int
	failCount int

	snakeCase = regexp.MustCompile(`^[a-z][a-z0-9]*(_[a-z0-9]+)*\.go$`)

	// 실제 Go interface 타입 선언(예: `type AccountRepository interface {`)만 매칭.
	// "type"·"interface"·"Repository" 단어가 파일 어딘가에 각각 등장하는 것만으로는
	// (예: struct 정의, 주석, 문자열, import 경로)는 매칭하지 않는다.
	repositoryInterfaceDecl = regexp.MustCompile(`(?m)^\s*type\s+\w*Repository\w*\s+interface\b`)
)

func pass(name string) {
	passCount++
	fmt.Printf("  PASS  %s\n", name)
}

func fail(name, reason string) {
	failCount++
	fmt.Printf("  FAIL  %s — %s\n", name, reason)
}

func section(name string) {
	fmt.Printf("\n[%s]\n", name)
}

func skip(name string) {
	fmt.Printf("  SKIP  %s\n", name)
}

func main() {
	root := "."
	if len(os.Args) > 1 {
		root = os.Args[1]
	}

	checkFileNaming(root)
	checkDirectoryStructure(root)
	checkRepositoryPlacement(root)
	checkHandlerPlacement(root)
	checkFilePlacement(root)
	checkSharedInfra(root)
	checkEventPlacement(root)

	fmt.Printf("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n")
	if failCount == 0 {
		fmt.Printf("%d passed  PASS\n", passCount)
	} else {
		fmt.Printf("%d passed, %d failed  FAIL\n", passCount, failCount)
		os.Exit(1)
	}
}

// [1] 파일명 snake_case 검사
func checkFileNaming(root string) {
	section("file-naming")
	found := false
	filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() {
			return nil
		}
		if !strings.HasSuffix(path, ".go") {
			return nil
		}
		name := d.Name()
		if strings.HasSuffix(name, "_test.go") || strings.HasSuffix(name, ".pb.go") {
			return nil
		}
		rel, _ := filepath.Rel(root, path)
		found = true
		if snakeCase.MatchString(name) {
			pass(rel)
		} else {
			fail(rel, "파일명은 snake_case.go 여야 함")
		}
		return nil
	})
	if !found {
		skip("Go 파일 없음")
	}
}

// [2] internal/ 디렉토리 구조 검사 (4레이어 + CQRS)
func checkDirectoryStructure(root string) {
	section("directory-structure")
	internal := filepath.Join(root, "internal")
	if _, err := os.Stat(internal); os.IsNotExist(err) {
		skip("internal/ 디렉토리 없음 — 검사 생략")
		return
	}
	for _, sub := range []string{"domain", "application", "infrastructure", "interface"} {
		dir := filepath.Join(internal, sub)
		rel, _ := filepath.Rel(root, dir)
		if _, err := os.Stat(dir); err == nil {
			pass(rel + "/")
		} else {
			fail(rel+"/", "디렉토리 없음")
		}
	}
	for _, sub := range []string{"command", "query"} {
		dir := filepath.Join(internal, "application", sub)
		rel, _ := filepath.Rel(root, dir)
		if _, err := os.Stat(dir); err == nil {
			pass(rel + "/")
		} else {
			fail(rel+"/", "디렉토리 없음")
		}
	}
}

// [3] Repository interface → domain/, 구현체 → infrastructure/
func checkRepositoryPlacement(root string) {
	section("repository-placement")
	found := false
	filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() || !strings.HasSuffix(path, ".go") {
			return nil
		}
		if strings.HasSuffix(d.Name(), "_test.go") {
			return nil
		}
		content, readErr := os.ReadFile(path)
		if readErr != nil {
			return nil
		}
		rel, _ := filepath.Rel(root, path)
		src := string(content)

		if repositoryInterfaceDecl.MatchString(src) {
			found = true
			if strings.Contains(filepath.ToSlash(path), "/domain/") {
				pass(rel + " (Repository interface)")
			} else {
				fail(rel, "Repository interface는 domain/ 패키지 안에 있어야 함")
			}
		}

		if strings.Contains(src, "var _") && strings.Contains(src, "Repository") &&
			strings.Contains(src, "nil") {
			found = true
			if strings.Contains(filepath.ToSlash(path), "/infrastructure/") {
				pass(rel + " (Repository impl 검증)")
			} else {
				fail(rel, "Repository 구현체는 infrastructure/ 패키지 안에 있어야 함")
			}
		}
		return nil
	})
	if !found {
		skip("Repository 정의 없음")
	}
}

// [4] Handler 파일 위치: CQRS 핸들러 → application/command|query/, HTTP 핸들러 → interface/http/
func checkHandlerPlacement(root string) {
	section("handler-placement")
	found := false
	filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() || !strings.HasSuffix(path, ".go") {
			return nil
		}
		name := d.Name()
		rel, _ := filepath.Rel(root, path)
		pathSlash := filepath.ToSlash(path)

		if strings.HasSuffix(name, "_handler.go") &&
			!strings.HasSuffix(name, "_event_handler.go") {
			found = true
			// guide.md가 문서화한 HTTP 핸들러 위치(interface/http/)에 있으면
			// CQRS 핸들러(application/command|query/) 규칙 대상에서 제외하고 그대로 통과시킨다.
			switch {
			case strings.Contains(pathSlash, "/interface/http/"):
				pass(rel + " (HTTP handler)")
			case strings.Contains(pathSlash, "/application/command/"),
				strings.Contains(pathSlash, "/application/query/"):
				pass(rel)
			default:
				fail(rel, "handler 파일은 application/command/, application/query/ 또는 interface/http/ 에 있어야 함")
			}
		}
		return nil
	})
	if !found {
		skip("handler 파일 없음")
	}
}

// [5] 파일명 suffix 기반 레이어 배치 규칙
func checkFilePlacement(root string) {
	section("file-placement")
	filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() || !strings.HasSuffix(path, ".go") {
			return nil
		}
		name := d.Name()
		rel, _ := filepath.Rel(root, path)
		pathSlash := filepath.ToSlash(path)

		switch {
		case strings.HasSuffix(name, "_task_controller.go"):
			if strings.Contains(pathSlash, "/interface/") {
				pass(rel)
			} else {
				fail(rel, "task_controller 파일은 interface/ 에 있어야 함")
			}
		case strings.HasSuffix(name, "_scheduler.go"):
			if strings.Contains(pathSlash, "/infrastructure/") {
				pass(rel)
			} else {
				fail(rel, "scheduler 파일은 infrastructure/ 에 있어야 함")
			}
		}
		return nil
	})
}

// [6] shared-infra: outbox·task-queue 패턴
//
// outbox/task-queue 관련 파일이 있다면, 그 코드가 전용 디렉토리(디렉토리명이
// 정확히 "outbox"/"task-queue")에 모여 있어야 한다. shared-modules.md는 이
// 디렉토리의 위치를 internal/ 바로 아래로 고정하지 않는다 — 여러 관심사별
// 하위 패키지(internal/infrastructure/outbox/ 등) 어디에 두어도 되므로,
// 존재 여부도 파일 스캔과 마찬가지로 internal/ 전체를 재귀적으로 뒤져 확인한다.
func checkSharedInfra(root string) {
	section("shared-infra")

	hasOutboxFile := false
	hasTaskFile := false
	hasOutboxDir := false
	hasTaskDir := false
	filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
		if err != nil {
			return nil
		}
		name := d.Name()
		pathSlash := filepath.ToSlash(path)
		if d.IsDir() {
			if name == "outbox" {
				hasOutboxDir = true
			}
			if name == "task-queue" {
				hasTaskDir = true
			}
			return nil
		}
		if strings.Contains(name, "outbox") && !strings.Contains(pathSlash, "/outbox/") {
			hasOutboxFile = true
		}
		if strings.Contains(name, "task_queue") && !strings.Contains(pathSlash, "/task-queue/") {
			hasTaskFile = true
		}
		return nil
	})

	if hasOutboxFile {
		if hasOutboxDir {
			pass("internal/**/outbox/")
		} else {
			fail("internal/**/outbox/", "outbox 파일이 있으나 전용 outbox/ 디렉토리 없음")
		}
	} else {
		skip("outbox 패턴 없음")
	}

	if hasTaskFile {
		if hasTaskDir {
			pass("internal/**/task-queue/")
		} else {
			fail("internal/**/task-queue/", "task 파일이 있으나 전용 task-queue/ 디렉토리 없음")
		}
	} else {
		skip("task-queue 패턴 없음")
	}
}

// [7] 이벤트 핸들러·인티그레이션 이벤트 배치
func checkEventPlacement(root string) {
	section("event-placement")
	found := false
	filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() || !strings.HasSuffix(path, ".go") {
			return nil
		}
		name := d.Name()
		rel, _ := filepath.Rel(root, path)
		pathSlash := filepath.ToSlash(path)

		if strings.HasSuffix(name, "_event_handler.go") {
			found = true
			if strings.Contains(pathSlash, "/application/event/") {
				pass(rel)
			} else {
				fail(rel, "이벤트 핸들러는 application/event/ 에 있어야 함")
			}
		}
		if strings.HasSuffix(name, "_integration_event.go") {
			found = true
			if strings.Contains(pathSlash, "/application/integration-event/") {
				pass(rel)
			} else {
				fail(rel, "integration event는 application/integration-event/ 에 있어야 함")
			}
		}
		return nil
	})
	if !found {
		skip("이벤트 핸들러 없음")
	}
}

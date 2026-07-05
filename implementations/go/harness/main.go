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

	snakeCase  = regexp.MustCompile(`^[a-z][a-z0-9]*(_[a-z0-9]+)*\.go$`)
	domainDirs = map[string]bool{
		"cmd": true, "internal": true, "pkg": true, "api": true,
	}
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
		// 생성된 파일 (.pb.go, _test.go 등) 제외
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

// [2] internal/ 디렉토리 구조 검사
func checkDirectoryStructure(root string) {
	section("directory-structure")
	internal := filepath.Join(root, "internal")
	if _, err := os.Stat(internal); os.IsNotExist(err) {
		skip("internal/ 디렉토리 없음 — 검사 생략")
		return
	}
	for _, sub := range []string{"domain", "application", "infrastructure"} {
		dir := filepath.Join(internal, sub)
		rel, _ := filepath.Rel(root, dir)
		if _, err := os.Stat(dir); err == nil {
			pass(rel + "/")
		} else {
			fail(rel+"/", "디렉토리 없음")
		}
	}
	// application 하위 command/query 분리
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
		content, readErr := os.ReadFile(path)
		if readErr != nil {
			return nil
		}
		rel, _ := filepath.Rel(root, path)
		src := string(content)

		// interface Repository 정의 파일: domain/ 안에 있어야 함
		if strings.Contains(src, "type") && strings.Contains(src, "interface") &&
			strings.Contains(src, "Repository") {
			found = true
			if strings.Contains(filepath.ToSlash(path), "/domain/") {
				pass(rel + " (Repository interface)")
			} else {
				fail(rel, "Repository interface는 domain/ 패키지 안에 있어야 함")
			}
		}

		// var _ XxxRepository = (*Xxx)(nil) 패턴: infrastructure/ 안에 있어야 함
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

// [4] Handler 파일 위치: command → application/command/, query → application/query/
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

		if strings.HasSuffix(name, "_handler.go") {
			found = true
			if strings.Contains(pathSlash, "/application/command/") ||
				strings.Contains(pathSlash, "/application/query/") {
				pass(rel)
			} else {
				fail(rel, "handler 파일은 application/command/ 또는 application/query/ 에 있어야 함")
			}
		}
		return nil
	})
	if !found {
		skip("handler 파일 없음")
	}
}

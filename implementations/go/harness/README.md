# Harness — Go 프로젝트 구조·네이밍 규칙 검사

`docs/`(공통) + `docs/architecture/*.md`(Go 구현)의 가이드 규칙 중 **기계 검증 가능한 항목**을 외부 Go 프로젝트에 적용하는 정적 분석 도구. 설계 원칙은 루트 [`../../../docs/harness.md`](../../../docs/harness.md)를 따른다 — 아키텍처 규칙 준수만 평가하고, `examples/`의 Account 도메인 같은 특정 업무 도메인 지식을 전제로 삼지 않는다.

## 구조

```
harness/
  main.go                        CLI 엔트리 — 규칙 목록 정의 + 결과 집계/출력
  rule.go                        공통 타입(Finding, RuleResult, Kind)
  file_naming.go                 규칙별 구현 파일 (규칙 하나당 파일 하나)
  directory_structure.go
  repository_placement.go
  handler_placement.go
  file_placement.go
  shared_infra.go
  event_placement.go
  outbox_drain_order.go
  *_test.go                      규칙별 회귀 테스트 (표준 testing 패키지)
  testdata/<rule>/good/          해당 규칙을 통과해야 하는 최소 fixture
  testdata/<rule>/bad-*/         해당 규칙을 위반해 실패해야 하는 fixture
```

각 규칙 함수는 `func(root string) RuleResult` 시그니처를 가지며, `main.go`의 `rules` 슬라이스에 등록된 순서대로 실행·출력된다.

## 사용

```bash
# 저장소 루트에서
bash implementations/go/harness.sh <projectRoot>

# 또는 harness/ 디렉토리에서 직접
cd implementations/go/harness
go run . <projectRoot>
```

## 규칙 목록

| 이름 | 파일 | 역할 |
|------|------|------|
| `file-naming` | `file_naming.go` | 파일명이 `snake_case.go`인지 |
| `directory-structure` | `directory_structure.go` | `internal/{domain,application,infrastructure,interface}` + `application/{command,query}` 존재 |
| `repository-placement` | `repository_placement.go` | Repository interface → `domain/`, 구현체(`var _ ... = (*Impl)(nil)`) → `infrastructure/` |
| `handler-placement` | `handler_placement.go` | `*_handler.go` → `application/command\|query/` 또는 `interface/http/` |
| `file-placement` | `file_placement.go` | `*_task_controller.go` → `interface/`, `*_scheduler.go` → `infrastructure/` |
| `shared-infra` | `shared_infra.go` | `OutboxWriter` 참조 시 `outbox/`에 `Writer`/`Poller`/`Consumer` 타입 구현 확인, `*task_queue*` 참조 시 `task-queue/` 배치 확인 |
| `event-placement` | `event_placement.go` | `*_event_handler.go` → `application/event/`, `*_integration_event.go` → `application/integration-event/` |
| `outbox-drain-order` | `outbox_drain_order.go` | Command Handler(`*_handler.go`, `*_event_handler.go` 제외)가 `OutboxRelay`/`OutboxPoller`/`OutboxConsumer`를 참조하거나 `ProcessPending`/`Poll`/`drainOnce`류를 호출하면 실패 — 저장 후 곧바로 반환해야 하며 Outbox → SQS 발행/수신은 독립적으로 주기 실행되는 Poller/Consumer만의 책임이다(동기 드레인 금지, domain-events.md) |
| `cqrs-pattern` | `cqrs_pattern.go` | `application/command`·`application/query` 디렉토리 존재, `application/query/` 파일이 쓰기 전용 `Repository` 타입을 참조하지 않는지(nestjs의 `cqrs-pattern.evaluator.ts` 이식) |

## 회귀 테스트

```bash
cd implementations/go/harness
go test ./...          # 규칙별 fixture 테스트
go vet ./...           # 정적 검사
```

각 규칙은 최소 `testdata/<rule>/good/`(통과해야 함)와 `testdata/<rule>/bad-*/`(실패해야 함) fixture로 검증된다. `testdata/`는 Go 툴체인이 자동으로 무시하는 표준 디렉토리라 별도 설정이 필요 없다.

새 규칙을 추가하거나 기존 규칙을 수정할 때는:
1. 해당 `<rule>.go` 파일에 로직 구현(또는 수정)
2. `testdata/<rule>/good/`, `testdata/<rule>/bad-*/` fixture 작성
3. `<rule>_test.go`에 fixture를 검증하는 테스트 추가
4. `main.go`의 `rules` 슬라이스에 등록(신규 규칙인 경우)
5. `go test ./... && go vet ./...`

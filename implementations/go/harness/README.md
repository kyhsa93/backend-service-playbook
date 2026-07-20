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
  cqrs_pattern.go
  domain_layer_isolation.go
  interface_no_infrastructure.go
  no_cross_aggregate_reference.go
  no_direct_env_access.go
  cross_bc_application_import.go
  no_logging_in_domain.go
  scheduler_in_infrastructure_only.go
  no_silent_catch.go
  dockerfile_conventions.go
  import_paths.go                go/parser 기반 import 경로 추출 헬퍼(여러 규칙이 공유)
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
| `repository-naming` | `repository_naming.go` | `domain/` 레이어 Repository·Query interface의 메서드명이 `find<Noun>s`/`save<Noun>`/`delete<Noun>` 컨벤션을 따르는지(`FindBy*`, 바레 `FindAll`/`Save`/`Delete`, `Count*`, `Update*` 블록리스트 — `Update*`는 별도 수정 메서드 금지) — `repository-pattern.md` |
| `handler-placement` | `handler_placement.go` | `*_handler.go` → `application/command\|query/` 또는 `interface/http/` |
| `file-placement` | `file_placement.go` | `*_task_controller.go` → `interface/`, `*_scheduler.go` → `infrastructure/` |
| `shared-infra` | `shared_infra.go` | `OutboxWriter` 참조 시 `outbox/`에 `Writer`/`Poller`/`Consumer` 타입 구현 확인, `*task_queue*` 참조 시 `task-queue/` 배치 확인 |
| `event-placement` | `event_placement.go` | `*_event_handler.go` → `application/event/`, `*_integration_event.go` → `application/integration-event/` |
| `outbox-drain-order` | `outbox_drain_order.go` | Command Handler(`*_handler.go`, `*_event_handler.go` 제외)가 `OutboxRelay`/`OutboxPoller`/`OutboxConsumer`를 참조하거나 `ProcessPending`/`Poll`/`drainOnce`류를 호출하면 실패 — 저장 후 곧바로 반환해야 하며 Outbox → SQS 발행/수신은 독립적으로 주기 실행되는 Poller/Consumer만의 책임이다(동기 드레인 금지, domain-events.md) |
| `cqrs-pattern` | `cqrs_pattern.go` | `application/command`·`application/query` 디렉토리 존재, `application/query/` 파일이 쓰기 전용 `Repository` 타입을 참조하지 않는지(nestjs의 `cqrs-pattern.evaluator.ts` 이식) |
| `domain-layer-isolation` | `domain_layer_isolation.go` | `internal/domain/**/*.go`가 `internal/application\|infrastructure\|interface/`를 import하지 않는지(경로 세그먼트 기반, 특정 라이브러리명 블록리스트가 아님) — `layer-architecture.md` |
| `interface-no-infrastructure` | `interface_no_infrastructure.go` | `internal/interface/**/*.go`(HTTP 핸들러/라우터)가 `internal/infrastructure/`를 직접 import하지 않는지 — `application/`에만 의존해야 함 — `layer-architecture.md` |
| `no-cross-aggregate-reference` | `no_cross_aggregate_reference.go` | `internal/domain/payment/`의 `Payment`/`Refund` Aggregate가 서로를 struct 필드로 직접 참조하지 않는지(ID 문자열 참조만 허용) — `domain-service.md` |
| `no-direct-env-access-outside-config` | `no_direct_env_access.go` | `internal/domain/`, `internal/application/`이 `os.Getenv`/`os.LookupEnv`를 직접 호출하지 않는지 — 환경 변수 검증은 `internal/config/`로만 모음 — `config.md` |
| `no-cross-bc-repository-in-application` | `cross_bc_application_import.go` | `internal/application/`의 한 파일이 서로 다른 Bounded Context의 `internal/domain/<bc>/` 패키지를 동시에 import하지 않는지 — 다른 BC는 `infrastructure/acl` Adapter로만 접근 — `cross-domain-communication.md` |
| `no-logging-in-domain` | `no_logging_in_domain.go` | `internal/domain/**/*.go`가 `log`/`log/slog` 등 로깅 라이브러리를 import하지 않는지 — `observability.md` |
| `scheduler-in-infrastructure-only` | `scheduler_in_infrastructure_only.go` | `time.Ticker`/`time.NewTicker`/cron 라이브러리 참조가 `internal/domain/`·`internal/application/`에 없는지(`internal/infrastructure/`에서만 허용) — `scheduling.md` |
| `no-silent-catch` | `no_silent_catch.go` | `if err != nil { }`처럼 완전히 빈 블록으로 에러를 조용히 삼키는 곳이 없는지 — `observability.md` |
| `dockerfile-conventions` | `dockerfile_conventions.go` | `examples/Dockerfile`이 멀티스테이지(FROM 2개 이상)+`HEALTHCHECK`를 갖추고, 옆의 `.dockerignore`가 `.git`/`.env` 등을 제외하는지 — `container.md` |

**구현하지 않은 규칙:** `aggregate-no-public-setters`는 이 저장소의 실제 Aggregate(Account/Card/Payment/Refund/Credential)가 모두 필드를 **전부 exported**로 선언하는 컨벤션을 쓴다(Go는 필드 단위 접근 제어가 없고, 이 저장소는 "비공개 필드 + 접근자 메서드"가 아니라 "공개 필드 + 도메인 메서드로 상태 전이"를 선택했다) — 그래서 애초에 "숨겨야 할 비공개 필드"라는 전제가 성립하지 않는다. "다른 패키지가 도메인 메서드를 우회해 필드를 직접 대입하는지"를 잡으려면 각 대입식이 실제로 Aggregate 타입 변수를 대상으로 하는지 타입 추론이 필요한데, 필드 이름(`Status`, `Amount` 등)이 DTO/테스트 코드에서도 흔히 재사용되는 이 저장소 스타일상 정규식 기반으로는 오탐률이 지나치게 높아 규칙을 추가하지 않았다.

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

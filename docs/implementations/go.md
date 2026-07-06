# Go 구현체

## 개요

Go는 표준 라이브러리(`net/http`, `database/sql`)와 최소한의 서드파티 의존성만으로 이 플레이북의 원칙을 구현하는 언어별 구현체다.
이 플레이북의 원칙을 Go로 구체적으로 구현한 가이드와 실행 가능한 예제는 이 저장소 안의 `implementations/go/`에 있다.

**→ [implementations/go/docs/guide.md](../../implementations/go/docs/guide.md)** — Go 구현 상세 가이드 (디렉토리 구조, 네이밍, Repository/CQRS/에러 처리 패턴, DI)
**→ [implementations/go/examples/](../../implementations/go/examples/)** — Account 도메인 전체 구현 예시 (계좌 개설/입출금/정지/재개/종료 + 이메일 알림)
**→ [implementations/go/harness/](../../implementations/go/harness/)** — 가이드 준수 여부를 검증하는 자동 evaluator

이 문서는 root `docs/architecture/*` 24개 문서 각각을 Go 구현체(`guide.md` 본문 + `examples/` 코드 + `harness/`)가 얼마나, 어떻게 다루는지 감사(audit)한 결과다. `guide.md` 자체는 258줄로 매우 짧고 6개 주제(디렉토리 구조, 네이밍, Repository, CQRS, 에러 처리, Soft Delete, DI)만 명시적으로 다룬다 — 그 밖의 항목은 `examples/`의 Account 도메인 코드에서만 확인할 수 있거나, 아예 어디에도 없다.

---

## Root 문서 대비 Go 커버리지

범례: **Covered** = guide.md 또는 examples/가 Go답게 실제로 다룸· **Thin** = 다루지만 얕거나 부분적·root 규칙과 어긋나는 부분 있음 · **Missing** = guide.md/examples/harness 어디에도 없음 · **N/A** — 해당 없음(이 표에서는 사용하지 않음, 아래 "낮은 우선순위" 참고)

| 원칙 문서 (루트, 공용) | 상태 | Go 커버리지 위치 / 메커니즘 |
|---|---|---|
| [layer-architecture.md](../architecture/layer-architecture.md) | Covered | `guide.md` 디렉토리 구조 + `internal/domain\|application\|infrastructure\|interface` 4레이어, 의존 방향 일치. 단, Query Handler(`get_account_handler.go`)가 별도 Query 인터페이스 없이 쓰기와 동일한 `account.Repository`를 그대로 사용 — root가 요구하는 "Query Service는 별도 읽기 전용 인터페이스만 사용" 원칙과는 다르다. |
| [repository-pattern.md](../architecture/repository-pattern.md) | Thin | `guide.md` — domain 패키지에 `interface`, infrastructure에 구현체 + `var _ Repository = (*Impl)(nil)` 컴파일 타임 검증(Go 고유 메커니즘). 다만 root의 "조회는 `find<Noun>s` 하나, 단건은 `take:1`" 규칙 대신 `FindByID`/`FindAll` 두 메서드로 분리되어 있고, `Delete<Noun>` 메서드가 아예 없다(Account는 Close()로 상태만 바꿈 — 삭제 자체를 다루지 않음). |
| [persistence.md](../architecture/persistence.md) | Thin | Soft Delete는 `guide.md`에 명시(`DeletedAt *time.Time`, `deleted_at IS NULL` 필터) + `examples/`에서 실제 사용 확인. 반면 트랜잭션 전파(Unit of Work)는 `account_repository.go`의 `Save()` 내부 로컬 `db.BeginTx()`뿐 — root가 요구하는 "여러 Repository를 하나의 트랜잭션으로 묶는 context 기반 TransactionManager" 패턴은 guide.md에도, examples/에도 없다. 마이그레이션은 순번 SQL 파일(`0001_init.sql`, `0002_...`)만 있고 롤백(down) 스크립트는 없다. |
| [domain-events.md](../architecture/domain-events.md) | Thin | Aggregate가 `DomainEvent` 인터페이스 + `events []DomainEvent` 슬라이스로 이벤트를 수집하는 패턴은 잘 구현됨(`account.go`, `events.go`). 그러나 **Outbox 패턴이 없다** — `notification.Service.Notify()`는 `repo.Save()` 커밋이 끝난 뒤 별도로 호출되는 best-effort 부가 호출이며 실패해도 삼켜진다(`service.go` 주석에 명시). 이는 root가 명시적으로 경계하는 dual-write 문제 그 자체다. 멱등성 3단계 전략, Integration Event 개념도 다루지 않는다. |
| [cqrs-pattern.md](../architecture/cqrs-pattern.md) | Covered | `guide.md`에 `XxxHandler` 구조체 + `Handle(ctx, cmd/query) (result, error)` 메서드 패턴 명시, examples/의 8개 커맨드/쿼리 핸들러가 일관되게 이 패턴을 따름. Command Bus/Query Bus 대신 `router.go`에서 핸들러를 직접 조립·호출(경량 CQRS에 해당, root 문서가 인정하는 방식). |
| [error-handling.md](../architecture/error-handling.md) | Thin | `guide.md` — sentinel error(`var ErrXxx = errors.New(...)`) + `errors.Is`로 HTTP 상태 코드 매핑(`writeAccountError`). 레이어 분리(Domain/Application은 plain error, Interface에서만 HTTP 변환)는 정확히 지켜짐. 다만 root가 요구하는 표준 에러 응답 JSON 스키마(`statusCode`/`code`/`message`/`error`)는 구현되지 않음 — `http.Error(w, err.Error(), status)`로 평문 텍스트만 반환한다. |
| [api-response.md](../architecture/api-response.md) | Covered (examples만) | `page`/`take` 페이지네이션(0-base, 기본값 20), 목록 응답이 도메인 복수형 키(`transactions`) + `count` 사용 — root 규칙과 일치. `guide.md` 본문에는 이 내용이 전혀 없고 오직 `examples/`에서만 확인 가능. |
| [authentication.md](../architecture/authentication.md) | Missing | JWT 발급/검증 흐름이 전혀 없다. `account_handler.go`는 `X-User-Id` 헤더 값을 검증 없이 그대로 신뢰해 `RequesterID`로 사용한다 — 인증이 아니라 인증을 생략한 자리표시자(placeholder)에 가깝다. |
| [cross-cutting-concerns.md](../architecture/cross-cutting-concerns.md) | Missing | Correlation ID 주입, 인증 Guard, 입력 검증 Pipe, 응답 로깅 Interceptor로 이어지는 요청 파이프라인 개념이 guide.md/examples 어디에도 없다. `account_handler.go`가 입력 검증(이메일 형식)과 요청 처리를 같은 함수 안에서 함께 수행한다. |
| [cross-domain-communication.md](../architecture/cross-domain-communication.md) | Missing | Account가 유일한 BC인 예제라 Adapter/Integration Event를 보여줄 장면 자체가 없다. Go의 interface가 Adapter 패턴 구현에 그대로 재사용 가능하므로(= repository-pattern과 동일한 메커니즘) 상대적으로 낮은 우선순위다. |
| [directory-structure.md](../architecture/directory-structure.md) | Covered | `internal/domain`, `application/command\|query`, `infrastructure`, `interface/http` 구조와 `snake_case.go` 파일명, `PascalCase` 타입명 규칙을 `guide.md`가 명시하고 harness가 일부 검사. 단, root의 `common/`, `config/`, `database/`(TransactionManager), `outbox/`, `task-queue/` 같은 공용 인프라 디렉토리는 (해당 패턴 자체가 구현되지 않았으므로) Go 쪽 구조에 없다. |
| [scheduling.md](../architecture/scheduling.md) | Missing | Scheduler, Task Queue, Task Outbox, DLQ 등 어떤 개념도 다루지 않는다. |
| [observability.md](../architecture/observability.md) | Missing | 구조화 로그(JSON, snake_case 필드), 로그 레벨 정책, Correlation ID 전파 모두 없음. `notification/service.go`가 `log.Printf`로 평문 문자열만 남긴다. |
| [graceful-shutdown.md](../architecture/graceful-shutdown.md) | Missing | `cmd/server/main.go`가 `http.ListenAndServe`를 그대로 호출하고 종료 — SIGTERM 처리, readiness/liveness 프로브, 순서 있는 리소스 정리 없음. |
| [container.md](../architecture/container.md) | Missing | `implementations/go/` 어디에도 Dockerfile이 없다. 멀티스테이지 빌드, `.dockerignore`, 헬스체크 엔드포인트 모두 다루지 않는다. |
| [config.md](../architecture/config.md) | Missing | `main.go`가 `os.Getenv("DATABASE_URL")`을 검증 없이 바로 사용한다. Fail-fast 검증, 관심사별 설정 파일 분리 모두 없음. |
| [secret-manager.md](../architecture/secret-manager.md) | Missing | SecretService 인터페이스나 TTL 캐시가 없다. SES 자격증명은 `NewSESClient()`에서 환경 변수로 직접 읽는다(secret-manager가 아니라 config/local-dev 수준의 처리). |
| [local-dev.md](../architecture/local-dev.md) | Thin | `examples/docker-compose.yml`이 Postgres + LocalStack(SES) healthcheck를 포함해 root 패턴을 잘 따른다. 다만 `profiles: [app]` 앱 서비스 분리, `.env.development`/`.env.docker` 파일 구분은 없다. |
| [file-storage.md](../architecture/file-storage.md) | Missing | Presigned URL, StorageService 패턴 전혀 없음. |
| [testing.md](../architecture/testing.md) | Thin | E2E 테스트(`test/account_e2e_test.go`, `test/notification_e2e_test.go`)는 testcontainers(Postgres+LocalStack)로 충실하게 구현되어 있다. 그러나 root가 요구하는 3단계 중 나머지 둘 — 프레임워크 없는 Domain 단위 테스트, Repository를 mock으로 대체하는 Application 단위 테스트 — 가 `examples/`에 하나도 없다. |
| [aggregate-id.md](../architecture/aggregate-id.md) | Thin | ID는 `account.New()` 생성자(Domain 레이어)에서 생성되어 위치·주체 원칙은 지켜진다. 그러나 `uuid.NewString()`(하이픈 포함, `550e8400-e29b-...` 형식)을 사용해 root가 명시적으로 금지하는 "하이픈 포함 UUID"를 그대로 쓴다 — root는 하이픈 제거 32자리 hex를 요구한다. `guide.md` 본문에는 ID 생성 정책 자체가 없다. |
| [domain-service.md](../architecture/domain-service.md) | Thin | **Technical Service**는 정확히 구현됨: `command.Notifier`(Application 레이어 인터페이스) ← `notification.Service`(Infrastructure 구현체, SES + DB 기록), 생성자 주입. 반면 여러 Aggregate를 조율하는 **Domain Service**(상태 없는 도메인 판단 로직) 예시는 없다 — 예제가 단일 Aggregate(Account)라 필요 장면이 없었을 수 있다. `guide.md` 본문은 두 개념 모두 다루지 않는다. |
| [strategic-ddd.md](../architecture/strategic-ddd.md) | Missing | Subdomain 분류, BC 식별, Context Map 어느 것도 다루지 않는다. 언어 종속적인 내용이 아니므로 낮은 우선순위 — NestJS 구현체도 동일한 이유로 언어별 버전 없이 루트 문서를 그대로 참조한다. |
| [tactical-ddd.md](../architecture/tactical-ddd.md) | Covered (examples만) | Aggregate Root(`Account` — 불변식을 메서드 내부에서 검증, 외부에서 상태 직접 변경 불가), Entity(`Transaction` — ID로 동등성), Value Object(`Money` — 값 기반 동등성 `Equals()`), Domain Event(과거형 이름 `AccountCreated` 등)까지 네 가지 개념이 모두 `examples/internal/domain/account/`에 충실히 구현되어 있다. `guide.md` 본문에는 이 설명이 없고 root 문서를 그대로 참조하는 것을 전제로 한다. |
| [conventions.md](../conventions.md) | Thin | REST URL 설계(복수 명사 `/accounts`, 비-CRUD 행위 하위 경로 `/accounts/{id}/deposit` 등)는 examples/에서 root 규칙과 일치한다(guide.md 본문에는 언급 없음). Rate Limiting 섹션은 어디에도 대응하는 내용이 없다. 커밋 메시지/브랜치 네이밍은 언어 무관 규칙이라 별도 매핑이 필요 없다. |

> `docs/checklist.md`, `docs/development-process.md`는 에이전트 워크플로우/자기 검토 절차를 다루는 프로세스 문서로, 언어별 구현 상세와는 성격이 달라 이 표에 매핑하지 않았다(NestJS 구현체 문서도 동일).

---

## Harness가 실제로 검증하는 것 / 검증하지 않는 것

`implementations/go/harness/main.go`(307줄)는 아래 7개 구조·배치 규칙만 검사한다:

1. 파일명 `snake_case.go` 여부
2. `internal/{domain,application,infrastructure,interface}` + `application/{command,query}` 디렉토리 존재 여부
3. Repository interface가 `domain/` 아래, 구현체가 `infrastructure/` 아래 있는지
4. Handler 파일이 `application/command|query/` 또는 `interface/http/`에 있는지
5. `*_task_controller.go` → `interface/`, `*_scheduler.go` → `infrastructure/` 배치
6. outbox/task-queue 관련 파일이 있으면 `internal/outbox/`, `internal/task-queue/` 디렉토리가 존재하는지
7. `*_event_handler.go` → `application/event/`, `*_integration_event.go` → `application/integration-event/` 배치

**즉 harness는 순수하게 파일 위치·네이밍만 검사하며, guide.md가 다루는 주제 중 어느 것도 의미적으로 검증하지 않는다.** 구체적으로 다음은 guide.md에서 "Covered"로 분류된 주제이지만 harness는 전혀 확인하지 않는다:

- Repository 메서드 네이밍이 root 규칙(`find<Noun>s`/`save<Noun>`/`delete<Noun>`, update 메서드 금지)을 따르는지
- CQRS Handler가 실제로 `Handle(ctx, cmd/query) (result, error)` 시그니처를 갖는지
- 에러가 sentinel error인지, HTTP 상태 코드 매핑이 존재하는지
- Soft Delete 컬럼(`DeletedAt`)이 실제로 존재하고 조회 시 필터링되는지
- Aggregate ID가 root가 요구하는 하이픈 제거 32자리 hex 형식인지 (현재 examples/는 이 규칙을 어기고 있는데도 harness는 통과시킨다)
- Domain/Application 단위 테스트 파일의 존재 여부 (테스트 파일 자체를 검사 대상으로 삼지 않음)

---

## Go 전용, 대응 root 문서 없음

`guide.md`가 다루는 범위 안에서 root 문서에 대응물이 없는 내용은 다음 하나뿐이다:

- **컴파일 타임 interface 충족 검증** — `var _ order.Repository = (*OrderRepository)(nil)` 관용구. TypeScript의 구조적 타이핑이나 NestJS의 DI 토큰 바인딩과 달리, Go는 이 패턴으로 "구현체가 인터페이스를 만족하지 못하면 컴파일 자체가 실패"하는 정적 안전성을 얻는다. root의 어떤 문서도 이런 컴파일 타임 검증 메커니즘을 언급하지 않는다(TypeScript/Java/Kotlin은 `implements`/`extends` 키워드로 컴파일러가 자동 강제하므로 별도 관용구가 필요 없다).

그 외에는 — NestJS 구현체가 `module-pattern.md`, `bootstrap.md`, `shared-modules.md`, `design-principles.md`, `cross-domain.md` 등 5개의 고유 문서를 추가로 갖는 것과 달리 — Go `guide.md`는 root 개념을 Go 문법으로 옮기는 것 이상의 새로운 내용을 제공하지 않는다. `context.Context` 기반 취소 전파/값 전달, goroutine·channel을 활용한 동시성 패턴, `net/http` 1.22 라우팅 관용구 등 Go 고유의 실전 패턴은 guide.md에 전혀 등장하지 않는다 — 후속 보강 시 검토할 만한 후보다.

---

## Go 선택 이유

- **표준 라이브러리 우선** — `go.mod`의 직접 의존성은 AWS SDK, `google/uuid`, `lib/pq`, 테스트용 `testify`/`testcontainers`뿐이다. 웹 프레임워크(Gin/Echo 등)도, ORM도, DI 컨테이너도 쓰지 않고 `net/http`(Go 1.22+ 패턴 라우팅)와 `database/sql`만으로 전체 서비스가 동작한다 — 의존성 최소화가 실제 코드로 증명된다.
- **interface의 구조적 타이핑이 Repository/Technical Service 추상화에 자연스럽게 들어맞는다** — TypeScript의 `abstract class`나 NestJS의 DI 토큰 없이도, `domain/` 패키지의 `interface`와 `infrastructure/`의 구현체 + 컴파일 타임 assertion만으로 동일한 의존성 역전을 얻는다.
- **CQRS의 Handler 패턴이 프레임워크 없이 구조체 + 메서드만으로 표현된다** — `@nestjs/cqrs`의 `CommandBus`/`QueryBus` 같은 인프라 없이도 `XxxHandler` 구조체 하나가 하나의 유스케이스를 온전히 캡슐화한다.
- **정적 컴파일과 명시적 에러 처리(`error` 반환값)가 Domain/Application의 plain error 원칙과 궁합이 좋다** — `errors.Is`/`errors.New`/`fmt.Errorf("...: %w", err)`만으로 root가 요구하는 "레이어별 에러 처리 원칙"을 별도 예외 클래스 계층 없이 구현할 수 있다.

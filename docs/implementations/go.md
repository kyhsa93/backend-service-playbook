# Go 구현체

## 개요

Go는 표준 라이브러리(`net/http`, `database/sql`)와 최소한의 서드파티 의존성만으로 이 플레이북의 원칙을 구현하는 언어별 구현체다.
이 플레이북의 원칙을 Go로 구체적으로 구현한 가이드와 실행 가능한 예제는 이 저장소 안의 `implementations/go/`에 있다.

**→ [implementations/go/docs/architecture/](../../implementations/go/docs/architecture/)** — root `docs/architecture/*` 24개 문서 중 24개 각각에 대응하는 Go 구현 상세 21개 문서(전략적 설계·domain-service는 언어 무관이라 root를 그대로 참조, cross-domain-communication은 [`cross-domain.md`](../../implementations/go/docs/architecture/cross-domain.md)가 대응)
**→ [implementations/go/examples/](../../implementations/go/examples/)** — Account 도메인 전체 구현 예시 (계좌 개설/입출금/정지/재개/종료 + 이메일 알림)
**→ [implementations/go/harness/](../../implementations/go/harness/)** — 가이드 준수 여부를 검증하는 자동 evaluator

이 문서는 root `docs/architecture/*` 24개 문서 각각을 Go 구현체(`docs/architecture/*.md` 21개 문서 + `examples/` 코드 + `harness/`)가 얼마나, 어떻게 다루는지 매핑한 것이다. **문서화 수준과 `examples/` 코드가 원칙을 실제로 따르는지는 별개다.** 문서가 올바른 패턴을 설명하면서도 현재 코드의 격차를 명시하는 경우(UUID 하이픈 등)는 아래 표의 비고에 그대로 남겨두었다.

---

## Root 문서 대비 Go 커버리지

범례: **Covered** = `docs/architecture/*.md`가 Go답게 실제로 다루고, root 규칙을 정확히 설명함 (코드의 격차가 있으면 문서가 그 격차를 명시) · **Thin** = 다루지만 얕거나 부분적 · **Missing** = 문서화되지 않음 · **N/A** — 언어 무관이라 이 저장소에서 별도 매핑 없음

| 원칙 문서 (루트, 공용) | 상태 | Go 문서 / 현황 |
|---|---|---|
| [layer-architecture.md](../architecture/layer-architecture.md) | Covered | [`layer-architecture.md`](../../implementations/go/docs/architecture/layer-architecture.md) — 의존 방향, DI 없는 생성자 조립, `context.Context` 트랜잭션 전파를 정리. Query Handler(`get_account_handler.go`, `get_transactions_handler.go`)는 `internal/domain/account/repository.go`에 별도로 정의된 읽기 전용 `account.Query` 인터페이스에만 의존한다. `account.Repository`(Command)는 `Query`를 embed하고 `Save`를 더한 상위 인터페이스이고, `AccountRepository`(infrastructure) concrete 구현체 하나가 구조적 타이핑으로 양쪽을 모두 만족한다 — root의 "Query Service는 별도 읽기 전용 인터페이스만 사용" 원칙을 따른다. |
| [repository-pattern.md](../architecture/repository-pattern.md) | Covered | [`repository-pattern.md`](../../implementations/go/docs/architecture/repository-pattern.md) — domain 패키지 interface + infrastructure 구현체 + `var _ Repository = (*Impl)(nil)` 컴파일 타임 검증. 조회는 `FindAccounts(ctx, FindQuery)` 단일 메서드로 통일(root의 `find<Noun>s` 규칙과 일치) — 단건 조회는 `Take: 1` + `account.FindOne` 헬퍼로 재사용한다. `Delete<Noun>` 메서드는 여전히 없음(Account는 `Close()`로 상태만 전환하는 도메인 특성). |
| [persistence.md](../architecture/persistence.md) | Covered | [`persistence.md`](../../implementations/go/docs/architecture/persistence.md) — Soft Delete(`deleted_at IS NULL`), 마이그레이션 순번 파일 방식을 실제 코드 기준 설명. `context.Context` 기반 여러 Repository 통합 트랜잭션 전파(`database.WithTx` 패턴)는 실제로 두 Aggregate Repository에 동시에 쓰는 유스케이스가 없어 아직 만들지 않았다(YAGNI) — 목표 구현은 코드로 제시해 둔다. 마이그레이션 롤백은 각 `NNNN_*.sql`에 대응하는 `NNNN_*.down.sql`이 추가되어 해소됨. |
| [domain-events.md](../architecture/domain-events.md) | Covered | [`domain-events.md`](../../implementations/go/docs/architecture/domain-events.md) — Aggregate의 이벤트 수집(`events []DomainEvent`)과 Outbox 패턴(적재 + Relay 드레인)이 모두 실제로 구현되어 있음을 코드 기준으로 설명. `internal/infrastructure/persistence/account_repository.go`의 `Save`가 계좌/거래 row와 outbox row를 같은 트랜잭션에 커밋하고, Command Handler가 저장 직후 `internal/infrastructure/outbox/relay.go`의 `Relay.ProcessPending`을 동기 호출해 테이블 전체의 미처리 이벤트를 드레인한다(nestjs 구현체와 동일한 전략 — 별도 폴링 goroutine 없음). 개별 이벤트 처리 실패는 로그만 남기고 `processed`를 갱신하지 않아 다음 커맨드의 드레인 때 재시도된다. |
| [cqrs-pattern.md](../architecture/cqrs-pattern.md) | Covered | [`cqrs-pattern.md`](../../implementations/go/docs/architecture/cqrs-pattern.md) — `XxxHandler` 구조체 + `Handle(ctx, cmd/query) (result, error)` 패턴, Command Bus/Query Bus 없이 `router.go`에서 직접 조립하는 경량 CQRS 설명. |
| [error-handling.md](../architecture/error-handling.md) | Covered | [`error-handling.md`](../../implementations/go/docs/architecture/error-handling.md) — sentinel error + `fmt.Errorf("%w", ...)` 래핑 + `errors.Is` 매핑을 실제 `writeAccountError` 코드로 설명. `writeAccountError`가 `accountErrorMapping` 테이블(sentinel error → HTTP 상태 코드 → `ACCOUNT_NOT_FOUND` 같은 client-facing 코드)을 순회해 `writeJSONError`로 `{statusCode, code, message, error}` 표준 JSON 에러 응답(`internal/interface/http/dto.go`의 `ErrorResponse`)을 `Content-Type: application/json`으로 반환한다. |
| [api-response.md](../architecture/api-response.md) | Covered | [`api-response.md`](../../implementations/go/docs/architecture/api-response.md) — `page`/`take` 0-base 페이지네이션, 목록 응답의 도메인 복수형 키(`transactions`) + `count`가 root 규칙과 일치함을 실제 `dto.go`/`account_handler.go` 코드로 확인. |
| [authentication.md](../architecture/authentication.md) | Covered | [`authentication.md`](../../implementations/go/docs/architecture/authentication.md) — root의 JWT/Bearer + Interface 레이어 전용 검증 원칙과 `net/http` 미들웨어 기반 구현(토큰 발급/검증, payload 최소화, 라우트 그룹 단위 미들웨어 적용)을 실제 코드로 설명. `account_handler.go`는 `middleware.UserIDFromContext`로 인증된 사용자 ID만 꺼낸다. |
| [cross-cutting-concerns.md](../architecture/cross-cutting-concerns.md) | Covered | [`cross-cutting-concerns.md`](../../implementations/go/docs/architecture/cross-cutting-concerns.md) — `func(http.Handler) http.Handler` 미들웨어 체인으로 인증/로깅/Correlation ID/입력 검증 역할을 분리. `internal/interface/http/middleware/`(`auth_middleware.go`/`rate_limit_middleware.go`/`correlation_id_middleware.go`/`logging_middleware.go`)가 실제로 구현되어 `router.go`에서 체이닝된다. `logging_middleware.go`의 `RequestLogging`이 모든 요청의 메서드/경로/상태 코드/소요 시간을 로깅한다. |
| [cross-domain-communication.md](../architecture/cross-domain-communication.md) | Covered | Go 전용 대응 문서는 [`cross-domain.md`](../../implementations/go/docs/architecture/cross-domain.md)(아래 "Go 전용" 절) — Account/Card 두 BC가 실제로 존재해 동기 Adapter(`command.AccountAdapter` ← `infrastructure/acl/account_adapter.go`)와 비동기 Integration Event(`account.suspended.v1`/`account.closed.v1`) 양쪽 모두 실제 코드로 구현되어 있다. |
| [directory-structure.md](../architecture/directory-structure.md) | Covered | [`directory-structure.md`](../../implementations/go/docs/architecture/directory-structure.md) — `internal/domain\|application\|infrastructure\|interface` 실제 트리를 파일 단위로 나열. `common/`(`id.go`), `config/`(`database.go`/`jwt.go`/`rate_limit.go`/`secret_service.go`), `outbox/`가 모두 실제로 구현되어 있다. `database/`(여러 Repository를 하나의 트랜잭션으로 묶는 공유 헬퍼), `task-queue/`만 그런 시나리오가 아직 없어 없음을 표로 설명. |
| [scheduling.md](../architecture/scheduling.md) | Covered | [`scheduling.md`](../../implementations/go/docs/architecture/scheduling.md) — `time.Ticker` 기반 Scheduler, Task Outbox, Task Consumer 목표 구현을 코드로 제시. **Missing 그대로 유지되는 부분 명시**: `examples/`에 스케줄링/Task Queue 예제 자체가 전혀 없음 — 이 문서는 전량 목표 설계다. |
| [observability.md](../architecture/observability.md) | Covered | [`observability.md`](../../implementations/go/docs/architecture/observability.md) — `log/slog` 구조화 로깅(JSON, snake_case 필드), 레이어별 로깅 기준, `context.Context` 기반 Correlation ID 전파를 실제 코드로 설명. `notification/service.go`/`outbox/relay.go`가 `slog`를 쓰고, `middleware/correlation_id_middleware.go`가 Correlation ID를 전파한다. `internal/infrastructure/logging.CorrelationHandler`가 `slog.Handler`를 감싸 모든 로그 레코드에 correlation_id를 자동 주입한다(`main.go`의 로거 초기화에 적용) — 호출부마다 수동으로 넘길 필요가 없다. |
| [graceful-shutdown.md](../architecture/graceful-shutdown.md) | Covered | [`graceful-shutdown.md`](../../implementations/go/docs/architecture/graceful-shutdown.md) — `signal.NotifyContext` + `http.Server.Shutdown(ctx)` 구현, liveness/readiness 엔드포인트 설계. `cmd/server/main.go`가 `signal.NotifyContext(context.Background(), syscall.SIGTERM, syscall.SIGINT)`로 종료 시그널을 받아 `srv.ListenAndServe()`를 goroutine에서 돌리고, `<-ctx.Done()` 이후 30초 타임아웃의 `srv.Shutdown(shutdownCtx)`로 진행 중인 요청을 마치고 종료한다. `internal/interface/http/health_handler.go`가 `/health/live`·`/health/ready`를 제공하고, `healthHandler.StartShutdown()`이 `srv.Shutdown()`보다 먼저 호출되어 readiness를 503으로 먼저 전환한다. |
| [container.md](../architecture/container.md) | Covered | [`container.md`](../../implementations/go/docs/architecture/container.md) — 멀티스테이지 빌드로 Go 정적 바이너리를 만드는 Go 고유 이점(런타임 이미지에 Go 툴체인/의존성 불필요, `distroless` 베이스)을 설명. `implementations/go/examples/Dockerfile` 존재, 이미지 14.3MB. |
| [config.md](../architecture/config.md) | Covered | [`config.md`](../../implementations/go/docs/architecture/config.md) — 설정 구조체 + 기동 시 fail-fast 검증. `main.go`가 `config.LoadDatabaseConfig()`로 `DATABASE_URL`을 검증하고 실패 시 `os.Exit(1)`. |
| [secret-manager.md](../architecture/secret-manager.md) | Covered | [`secret-manager.md`](../../implementations/go/docs/architecture/secret-manager.md) — AWS Secrets Manager 클라이언트 + `sync.Mutex` 기반 TTL 캐시, 이 저장소의 SES 클라이언트 관용구(정적 자격증명 + `AWS_ENDPOINT_URL` 분기)를 재사용. JWT secret이 프로덕션에서 이 경로로 조회된다(`internal/infrastructure/secret/service.go`). SES 자격증명은 민감값이 아니라 여전히 환경 변수 직접 참조(의도된 설계). |
| [local-dev.md](../architecture/local-dev.md) | Covered | [`local-dev.md`](../../implementations/go/docs/architecture/local-dev.md) — `examples/docker-compose.yml`(Postgres + LocalStack SES/Secrets Manager, healthcheck 포함)과 `localstack/init-ses.sh`/`init-secrets.sh`를 근거로 이미 실증된 패턴을 설명. `app` 서비스가 `profiles: [app]`으로 분리되어 있고, `.env.example`/`.env.development` 파일도 존재. |
| [file-storage.md](../architecture/file-storage.md) | Covered | [`file-storage.md`](../../implementations/go/docs/architecture/file-storage.md) — S3 Presigned URL(SDK v2 `s3.PresignClient`) 목표 구현, 이 저장소의 SES 클라이언트 관용구 재사용. **Missing 그대로 유지**: `examples/`에 파일 첨부 유스케이스 자체가 없어 전량 목표 설계. |
| [testing.md](../architecture/testing.md) | Covered | [`testing.md`](../../implementations/go/docs/architecture/testing.md) — E2E(`testcontainers-go`)를 근거로 설명하고, Domain 단위 테스트(table-driven, `package account_test`)와 Application 단위 테스트(수동 stub mock)를 실제 코드로 제시해 3단계 전략을 완성. `account_test.go`/`money_test.go`(Domain), `create_account_handler_test.go`/`deposit_handler_test.go`/`stub_test.go`(Application) 모두 존재. |
| [aggregate-id.md](../architecture/aggregate-id.md) | Covered | [`aggregate-id.md`](../../implementations/go/docs/architecture/aggregate-id.md) — root 규칙(UUID v4에서 하이픈 제거한 32자리 hex)을 명확히 기술하고 `common.NewID()` 유틸을 제시. `account.go`의 `New()`와 `transaction.go`의 `newTransaction()` 모두 `common.NewID()`를 쓴다. |
| [domain-service.md](../architecture/domain-service.md) | N/A | Go 전용 문서 없음 — Technical Service(`command.Notifier` ← `notification.Service`)는 [domain-events.md](../../implementations/go/docs/architecture/domain-events.md)/[secret-manager.md](../../implementations/go/docs/architecture/secret-manager.md)/[file-storage.md](../../implementations/go/docs/architecture/file-storage.md)에서 개별적으로 다룸. 여러 Aggregate를 조율하는 Domain Service는 예제가 단일 Aggregate라 여전히 예시가 없다 — root 문서를 그대로 참조. |
| [strategic-ddd.md](../architecture/strategic-ddd.md) | N/A | Subdomain/BC/Context Map은 언어 종속적이지 않아 root 문서를 그대로 참조 — NestJS 구현체도 동일한 이유로 별도 버전을 두지 않는다. |
| [tactical-ddd.md](../architecture/tactical-ddd.md) | Covered | [`tactical-ddd.md`](../../implementations/go/docs/architecture/tactical-ddd.md) — Aggregate Root/Entity/Value Object/Domain Event 네 가지 개념을 `internal/domain/account/` 실제 코드로 설명하고, **Go 고유의 구조적 제약**(패키지 단위 캡슐화만 가능, TypeScript/Java의 인스턴스 단위 `private`이 없음)을 별도 섹션으로 명시. |
| [conventions.md](../conventions.md) | Thin | REST URL 설계(복수 명사, `/accounts/{id}/deposit` 등 비-CRUD 하위 경로)는 `examples/`가 root 규칙과 일치하지만 별도 Go 문서는 없음(`api-response.md`에서 부분적으로 다룸). Rate Limiting 섹션은 아래 "Go 전용" 절의 [`rate-limiting.md`](../../implementations/go/docs/architecture/rate-limiting.md)(전역 limiter 구현됨)가 대응한다. 커밋 메시지/브랜치 네이밍은 언어 무관 규칙. |

> `docs/checklist.md`, `docs/development-process.md`는 에이전트 워크플로우/자기 검토 절차를 다루는 프로세스 문서로, 언어별 구현 상세와는 성격이 달라 이 표에 매핑하지 않았다(NestJS 구현체 문서도 동일).

---

## 요약 — 남아있는 코드 격차

`implementations/go/docs/architecture/*.md` 21개 문서는 각각 root 원칙을 설명하고, 가능한 곳은 실제 `examples/` 코드를 근거로 들며, 코드가 아직 원칙을 완전히 따르지 않는 지점은 "알려진 격차"로 명시하고 목표 구현을 코드로 제시한다. 아래는 **현재 코드에 남아있는 격차**다:

1. **persistence.md**: 컨텍스트 기반 여러 Repository 트랜잭션 전파 — 실제로 두 Aggregate의 Repository에 동시에 쓰는 Command Handler가 아직 없어 만들지 않음(YAGNI, `shared-modules.md`의 `tx.go` 부재 설명과 동일한 판단). 마이그레이션 롤백(`NNNN_*.down.sql`)은 각 up 파일에 대응하는 down 파일이 추가되어 해소됨.
2. **file-storage.md**, **scheduling.md**: 해당 패턴 자체가 `examples/`에 전혀 구현되어 있지 않음 — 문서는 전량 목표 설계.
3. **repository-pattern.md**: 해소됨 — `account.Repository`가 `FindAccounts(query)` 단일 메서드로 통일되었고(단건 조회는 `Take: 1` + `FindOne` 헬퍼), `Delete<Noun>` 부재는 여전히 의도된 설계(Account는 `Close()`로 상태만 전환).
4. **cross-cutting-concerns.md**: 해소됨 — `middleware/logging_middleware.go`(`RequestLogging`)가 메서드/경로/상태 코드/소요 시간을 로깅하고 `router.go`의 미들웨어 체인에 포함됨.
5. **observability.md**: 해소됨 — `internal/infrastructure/logging.CorrelationHandler`가 `slog.Handler`를 감싸 ctx의 correlation ID를 모든 로그 레코드에 자동 주입한다.
6. **error-handling.md**(rate-limiting.md와 공유): 해소됨 — `rate_limit_middleware.go`의 429 응답이 `{statusCode, code, message, error}` JSON 스키마(`RATE_LIMIT_EXCEEDED`)로 통일됨.

---

## Harness가 실제로 검증하는 것 / 검증하지 않는 것

`implementations/go/harness/main.go`(307줄)는 아래 7개 구조·배치 규칙만 검사한다:

1. 파일명 `snake_case.go` 여부
2. `internal/{domain,application,infrastructure,interface}` + `application/{command,query}` 디렉토리 존재 여부
3. Repository interface가 `domain/` 아래, 구현체가 `infrastructure/` 아래 있는지
4. Handler 파일이 `application/command|query/` 또는 `interface/http/`에 있는지
5. `*_task_controller.go` → `interface/`, `*_scheduler.go` → `infrastructure/` 배치
6. outbox/task-queue 관련 파일이 있으면 전용 `outbox/`, `task-queue/` 디렉토리(예: `internal/infrastructure/outbox/`)가 `internal/` 어딘가에 존재하는지
7. `*_event_handler.go` → `application/event/`, `*_integration_event.go` → `application/integration-event/` 배치

**harness는 순수하게 파일 위치·네이밍만 검사하며, `docs/architecture/*.md` 21개 문서가 다루는 주제 중 어느 것도 의미적으로 검증하지 않는다.** 다음은 harness가 잡아주지 않는 간극이다:

- Repository 메서드 네이밍이 root 규칙(`find<Noun>s`/`save<Noun>`/`delete<Noun>`)을 따르는지
- CQRS Handler가 실제로 `Handle(ctx, cmd/query) (result, error)` 시그니처를 갖는지
- 에러가 sentinel error인지, HTTP 상태 코드 매핑이 표준 JSON 스키마를 따르는지
- Soft Delete 컬럼이 실제로 존재하고 조회 시 필터링되는지
- Aggregate ID가 하이픈 제거 32자리 hex 형식인지(현재 `examples/`는 `common.NewID()`로 규칙을 지키고 있지만, harness는 이를 강제하지 않으므로 회귀해도 통과시킴)
- Domain/Application 단위 테스트 파일의 존재 여부

harness 규칙을 위 항목들로 확장하는 것이 향후 개선 방향으로 고려할 만하다.

---

## Go 전용, 대응 root 문서 없음

- **컴파일 타임 interface 충족 검증** — `var _ order.Repository = (*OrderRepository)(nil)` 관용구. TypeScript의 구조적 타이핑이나 NestJS의 DI 토큰 바인딩과 달리, Go는 이 패턴으로 "구현체가 인터페이스를 만족하지 못하면 컴파일 자체가 실패"하는 정적 안전성을 얻는다. root의 어떤 문서도 이런 컴파일 타임 검증 메커니즘을 언급하지 않는다.
- **패키지 단위 캡슐화** — Go에는 인스턴스 단위 `private`이 없다(`tactical-ddd.md` 참고). root 문서들은 이 제약을 전제하지 않는다.
- [`bootstrap.md`](../../implementations/go/docs/architecture/bootstrap.md) — `cmd/server/main.go` 실제 코드 기준 의존성 조립 순서(DI 컨테이너 없이 생성자 체이닝), config/미들웨어/graceful shutdown 개선을 통합한 목표 `main()` 형태.
- [`cross-domain.md`](../../implementations/go/docs/architecture/cross-domain.md) — Adapter 패턴 구현 상세(원칙은 root [cross-domain-communication.md](../architecture/cross-domain-communication.md) 참고). Account/Card 두 BC가 실제로 존재해 동기 Adapter와 비동기 Integration Event 양쪽 모두 실제 코드로 다룬다.
- [`design-principles.md`](../../implementations/go/docs/architecture/design-principles.md) — 나머지 20개 문서를 압축한 14개 항목 TL;DR 체크리스트.
- [`module-pattern.md`](../../implementations/go/docs/architecture/module-pattern.md) — Go에는 DI 컨테이너가 없다는 것을 전제로, 수동 생성자 조립과 패키지 트리가 NestJS `@Module`의 역할을 어떻게 대신하는지, 그리고 Go 컴파일러가 순환 의존을 `forwardRef()` 같은 우회 없이 원천 차단하는 이점을 설명.
- [`rate-limiting.md`](../../implementations/go/docs/architecture/rate-limiting.md) — `golang.org/x/time/rate` 토큰 버킷 기반 미들웨어. 전역 limiter는 `examples/`에 실제로 구현되어 `NewRouter`에 주입되고 쓰기 라우트 전체에 적용된다 — 문서의 클라이언트(IP)별 limiter map, 라우트별 차등 적용, `X-RateLimit-*` 헤더 노출은 아직 채택하지 않은 확장 옵션(forward-looking)으로 남아 있다. 초과 시 429 응답은 다른 에러 경로와 동일한 `{statusCode, code, message, error}` JSON 스키마(`RATE_LIMIT_EXCEEDED`)를 따른다.
- [`shared-modules.md`](../../implementations/go/docs/architecture/shared-modules.md) — `internal/common/`, `internal/interface/http/middleware/` 등 공유 코드 위치 컨벤션. NestJS의 `@Global` 모듈 선언과 달리 "공유"는 `main.go`가 같은 인스턴스를 여러 생성자에 전달하는 것으로 이루어짐을 설명.

---

## Go 선택 이유

- **표준 라이브러리 우선** — `go.mod`의 직접 의존성은 AWS SDK, `google/uuid`, `lib/pq`, 테스트용 `testify`/`testcontainers`뿐이다. 웹 프레임워크도, ORM도, DI 컨테이너도 쓰지 않는다.
- **interface의 구조적 타이핑이 Repository/Technical Service 추상화에 자연스럽게 들어맞는다.**
- **CQRS의 Handler 패턴이 프레임워크 없이 구조체 + 메서드만으로 표현된다.**
- **정적 컴파일과 명시적 에러 처리(`error` 반환값)가 Domain/Application의 plain error 원칙과 궁합이 좋다.**

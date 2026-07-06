# Go 구현체

## 개요

Go는 표준 라이브러리(`net/http`, `database/sql`)와 최소한의 서드파티 의존성만으로 이 플레이북의 원칙을 구현하는 언어별 구현체다.
이 플레이북의 원칙을 Go로 구체적으로 구현한 가이드와 실행 가능한 예제는 이 저장소 안의 `implementations/go/`에 있다.

**→ [implementations/go/docs/architecture/](../../implementations/go/docs/architecture/)** — root `docs/architecture/*` 24개 문서 중 24개 각각에 대응하는 Go 구현 상세 21개 문서(전략적 설계·domain-service·cross-domain-communication은 언어 무관이라 root를 그대로 참조)
**→ [implementations/go/examples/](../../implementations/go/examples/)** — Account 도메인 전체 구현 예시 (계좌 개설/입출금/정지/재개/종료 + 이메일 알림)
**→ [implementations/go/harness/](../../implementations/go/harness/)** — 가이드 준수 여부를 검증하는 자동 evaluator

이 문서는 root `docs/architecture/*` 24개 문서 각각을 Go 구현체(`docs/architecture/*.md` 21개 문서 + `examples/` 코드 + `harness/`)가 얼마나, 어떻게 다루는지 감사(audit)한 결과다. 이전 감사(단일 258줄 `guide.md` 기준)에서 Thin/Missing으로 분류됐던 대부분의 주제가 이번 패스에서 root 1:1 대응 문서로 새로 작성되어 Covered로 이동했다 — 다만 **문서화가 두터워졌다고 `examples/` 코드 자체가 바뀐 것은 아니다.** 문서가 올바른 패턴을 설명하면서도 현재 코드의 격차를 명시하는 경우(Outbox 미구현, UUID 하이픈 등)는 아래 표의 비고에 그대로 남겨두었다.

---

## Root 문서 대비 Go 커버리지

범례: **Covered** = `docs/architecture/*.md`가 Go답게 실제로 다루고, root 규칙을 정확히 설명함 (코드의 격차가 있으면 문서가 그 격차를 명시) · **Thin** = 다루지만 얕거나 부분적 · **Missing** = 문서화되지 않음 · **N/A** — 언어 무관이라 이 저장소에서 별도 매핑 없음

| 원칙 문서 (루트, 공용) | 상태 | Go 문서 / 현황 |
|---|---|---|
| [layer-architecture.md](../architecture/layer-architecture.md) | Covered | [`layer-architecture.md`](../../implementations/go/docs/architecture/layer-architecture.md) — 의존 방향, DI 없는 생성자 조립, `context.Context` 트랜잭션 전파를 정리. **알려진 격차 명시**: Query Handler(`get_account_handler.go`)가 별도 Query 인터페이스 없이 Command와 동일한 `account.Repository`를 재사용 — root의 "Query Service는 별도 읽기 전용 인터페이스만 사용" 원칙과 다름을 문서에 명시. |
| [repository-pattern.md](../architecture/repository-pattern.md) | Covered | [`repository-pattern.md`](../../implementations/go/docs/architecture/repository-pattern.md) — domain 패키지 interface + infrastructure 구현체 + `var _ Repository = (*Impl)(nil)` 컴파일 타임 검증. **알려진 격차 명시**: `FindByID`/`FindAll` 두 메서드 분리(root는 `find<Noun>s` 하나만 권장)와 `Delete<Noun>` 메서드 부재(Account는 `Close()`로 상태만 전환) 이유를 설명. |
| [persistence.md](../architecture/persistence.md) | Covered | [`persistence.md`](../../implementations/go/docs/architecture/persistence.md) — Soft Delete(`deleted_at IS NULL`), 마이그레이션 순번 파일 방식을 실제 코드 기준 설명. **알려진 격차 명시**: root가 요구하는 `context.Context` 기반 여러 Repository 통합 트랜잭션 전파는 미구현 — `Save()`가 로컬 `db.BeginTx()`만 사용. 목표 구현(`database.WithTx` 패턴)을 코드로 제시하되 아직 `examples/`에는 없음. 마이그레이션 롤백(down) 스크립트 부재도 명시. |
| [domain-events.md](../architecture/domain-events.md) | Covered | [`domain-events.md`](../../implementations/go/docs/architecture/domain-events.md) — Aggregate의 이벤트 수집(`events []DomainEvent`)은 정확히 구현됨을 확인. **알려진 격차를 가장 상세히 명시**: Outbox 패턴이 없고 `notification.Service.Notify()`가 `repo.Save()` 커밋 이후 별도 동기 호출되며 실패 시 로그만 남기고 삼켜지는 dual-write 안티패턴임을 코드 인용과 함께 설명, root가 요구하는 올바른 Outbox 스키마·Repository 통합 저장·Relay 목표 구현을 코드로 제시. |
| [cqrs-pattern.md](../architecture/cqrs-pattern.md) | Covered | [`cqrs-pattern.md`](../../implementations/go/docs/architecture/cqrs-pattern.md) — `XxxHandler` 구조체 + `Handle(ctx, cmd/query) (result, error)` 패턴, Command Bus/Query Bus 없이 `router.go`에서 직접 조립하는 경량 CQRS 설명. |
| [error-handling.md](../architecture/error-handling.md) | Covered | [`error-handling.md`](../../implementations/go/docs/architecture/error-handling.md) — sentinel error + `fmt.Errorf("%w", ...)` 래핑 + `errors.Is` 매핑을 실제 `writeAccountError` 코드로 설명. **알려진 격차 명시**: root가 요구하는 `{statusCode, code, message, error}` 표준 JSON 에러 응답 스키마가 없고 현재는 `http.Error`로 평문 텍스트만 반환 — 목표 구현을 코드로 제시. |
| [api-response.md](../architecture/api-response.md) | Covered | [`api-response.md`](../../implementations/go/docs/architecture/api-response.md) — `page`/`take` 0-base 페이지네이션, 목록 응답의 도메인 복수형 키(`transactions`) + `count`가 root 규칙과 일치함을 실제 `dto.go`/`account_handler.go` 코드로 확인. |
| [authentication.md](../architecture/authentication.md) | Covered | [`authentication.md`](../../implementations/go/docs/architecture/authentication.md) — root의 JWT/Bearer + Interface 레이어 전용 검증 원칙과 `net/http` 미들웨어 기반 목표 구현(토큰 발급/검증, payload 최소화, 클래스 레벨 대신 미들웨어 체인 적용)을 코드로 제시. **알려진 격차 명시**: 현재 `account_handler.go`는 `X-User-Id` 헤더를 검증 없이 그대로 신뢰하는 단순화된 자리표시자임을 명확히 표기. |
| [cross-cutting-concerns.md](../architecture/cross-cutting-concerns.md) | Covered | [`cross-cutting-concerns.md`](../../implementations/go/docs/architecture/cross-cutting-concerns.md) — `func(http.Handler) http.Handler` 미들웨어 체인으로 인증/로깅/Correlation ID/입력 검증 역할을 분리하는 목표 구현. 현재 `examples/`에는 미들웨어 체인 자체가 없고 `account_handler.go`가 검증과 처리를 한 함수에서 함께 수행하는 것을 명시. |
| [cross-domain-communication.md](../architecture/cross-domain-communication.md) | N/A | Go 전용 문서 없음 — Account가 유일한 BC라 Adapter/Integration Event를 보여줄 장면이 없다. Go의 interface가 Adapter 구현에 그대로 재사용 가능하므로([repository-pattern.md](../../implementations/go/docs/architecture/repository-pattern.md)의 컴파일 타임 검증과 동일 메커니즘) 상대적으로 낮은 우선순위 — root 문서를 그대로 참조. |
| [directory-structure.md](../architecture/directory-structure.md) | Covered | [`directory-structure.md`](../../implementations/go/docs/architecture/directory-structure.md) — `internal/domain\|application\|infrastructure\|interface` 실제 트리를 파일 단위로 나열하고, root의 `common/`, `database/`, `outbox/`, `task-queue/`, `config/` 공용 인프라 디렉토리가 왜 아직 없는지(대응 패턴 자체가 미구현이라서) 표로 설명. |
| [scheduling.md](../architecture/scheduling.md) | Covered | [`scheduling.md`](../../implementations/go/docs/architecture/scheduling.md) — `time.Ticker` 기반 Scheduler, Task Outbox, Task Consumer 목표 구현을 코드로 제시. **Missing 그대로 유지되는 부분 명시**: `examples/`에 스케줄링/Task Queue 예제 자체가 전혀 없음 — 이 문서는 전량 목표 설계다. |
| [observability.md](../architecture/observability.md) | Covered | [`observability.md`](../../implementations/go/docs/architecture/observability.md) — `log/slog` 구조화 로깅(JSON, snake_case 필드), 레이어별 로깅 기준, `context.Context` 기반 Correlation ID 전파 목표 구현. **알려진 격차 명시**: 현재 `notification/service.go`가 `log.Printf`로 평문만 남기고, 미들웨어 체인이 없어 Correlation ID 전파 자체가 미구현. |
| [graceful-shutdown.md](../architecture/graceful-shutdown.md) | Covered | [`graceful-shutdown.md`](../../implementations/go/docs/architecture/graceful-shutdown.md) — `signal.NotifyContext` + `http.Server.Shutdown(ctx)` 목표 구현, liveness/readiness 엔드포인트 설계. **알려진 격차 명시**: `cmd/server/main.go`가 현재 `http.ListenAndServe`를 블로킹 호출하고 끝나며 이 패턴을 전혀 쓰지 않음. |
| [container.md](../architecture/container.md) | Covered | [`container.md`](../../implementations/go/docs/architecture/container.md) — 멀티스테이지 빌드로 Go 정적 바이너리를 만드는 Go 고유 이점(런타임 이미지에 Go 툴체인/의존성 불필요, `scratch`/`distroless` 베이스 가능)을 설명. **알려진 격차 명시**: `implementations/go/`에 Dockerfile 자체가 아직 없음 — 문서의 예시가 목표 구현. |
| [config.md](../architecture/config.md) | Covered | [`config.md`](../../implementations/go/docs/architecture/config.md) — 설정 구조체 + 기동 시 fail-fast 검증 목표 패턴. **알려진 격차 명시**: `main.go`가 현재 `os.Getenv("DATABASE_URL")`을 검증 없이 바로 사용. |
| [secret-manager.md](../architecture/secret-manager.md) | Covered | [`secret-manager.md`](../../implementations/go/docs/architecture/secret-manager.md) — AWS Secrets Manager 클라이언트 + `sync.Mutex` 기반 TTL 캐시 목표 구현, 이 저장소의 SES 클라이언트 관용구(정적 자격증명 + `AWS_ENDPOINT_URL` 분기)를 그대로 재사용. **알려진 격차 명시**: SecretService 자체가 없고 SES 자격증명도 여전히 환경 변수 직접 참조. |
| [local-dev.md](../architecture/local-dev.md) | Covered | [`local-dev.md`](../../implementations/go/docs/architecture/local-dev.md) — `examples/docker-compose.yml`(Postgres + LocalStack SES, healthcheck 포함)과 `localstack/init-ses.sh`를 근거로 이미 실증된 패턴을 설명. **알려진 격차 명시**: `profiles: [app]` 앱 서비스 분리와 `.env.development`/`.env.docker` 파일 구분은 없음. |
| [file-storage.md](../architecture/file-storage.md) | Covered | [`file-storage.md`](../../implementations/go/docs/architecture/file-storage.md) — S3 Presigned URL(SDK v2 `s3.PresignClient`) 목표 구현, 이 저장소의 SES 클라이언트 관용구 재사용. **Missing 그대로 유지**: `examples/`에 파일 첨부 유스케이스 자체가 없어 전량 목표 설계. |
| [testing.md](../architecture/testing.md) | Covered | [`testing.md`](../../implementations/go/docs/architecture/testing.md) — E2E(`testcontainers-go`, 이미 구현됨)를 근거로 설명하고, Domain 단위 테스트(table-driven, `package account_test`)와 Application 단위 테스트(수동 stub mock)의 구체적 목표 예시 코드를 작성해 3단계 전략을 완성. **알려진 격차 명시**: 실제 `examples/`에는 Domain/Application 단위 테스트 파일이 아직 없음 — E2E만 실재. |
| [aggregate-id.md](../architecture/aggregate-id.md) | Covered | [`aggregate-id.md`](../../implementations/go/docs/architecture/aggregate-id.md) — root 규칙(UUID v4에서 하이픈 제거한 32자리 hex)을 명확히 기술하고 `common.NewID()` 목표 유틸을 제시. **알려진 격차 명시**: `account.go`의 `New()`와 `transaction.go`의 `newTransaction()`이 현재 `uuid.NewString()`을 하이픈 포함 그대로 사용 — root가 명시적으로 금지하는 형식임을 코드 인용과 함께 지적, 이번 패스에서 `examples/` 자체는 수정하지 않음. |
| [domain-service.md](../architecture/domain-service.md) | N/A | Go 전용 문서 없음 — Technical Service(`command.Notifier` ← `notification.Service`)는 [domain-events.md](../../implementations/go/docs/architecture/domain-events.md)/[secret-manager.md](../../implementations/go/docs/architecture/secret-manager.md)/[file-storage.md](../../implementations/go/docs/architecture/file-storage.md)에서 개별적으로 다룸. 여러 Aggregate를 조율하는 Domain Service는 예제가 단일 Aggregate라 여전히 예시가 없다 — root 문서를 그대로 참조. |
| [strategic-ddd.md](../architecture/strategic-ddd.md) | N/A | Subdomain/BC/Context Map은 언어 종속적이지 않아 root 문서를 그대로 참조 — NestJS 구현체도 동일한 이유로 별도 버전을 두지 않는다. |
| [tactical-ddd.md](../architecture/tactical-ddd.md) | Covered | [`tactical-ddd.md`](../../implementations/go/docs/architecture/tactical-ddd.md) — Aggregate Root/Entity/Value Object/Domain Event 네 가지 개념을 `internal/domain/account/` 실제 코드로 설명하고, **Go 고유의 구조적 제약**(패키지 단위 캡슐화만 가능, TypeScript/Java의 인스턴스 단위 `private`이 없음)을 별도 섹션으로 명시. |
| [conventions.md](../conventions.md) | Thin | REST URL 설계(복수 명사, `/accounts/{id}/deposit` 등 비-CRUD 하위 경로)는 `examples/`가 root 규칙과 일치하지만 별도 Go 문서는 없음(`api-response.md`에서 부분적으로 다룸). Rate Limiting 섹션은 여전히 대응 내용 없음. 커밋 메시지/브랜치 네이밍은 언어 무관 규칙. |

> `docs/checklist.md`, `docs/development-process.md`는 에이전트 워크플로우/자기 검토 절차를 다루는 프로세스 문서로, 언어별 구현 상세와는 성격이 달라 이 표에 매핑하지 않았다(NestJS 구현체 문서도 동일).

---

## 요약 — 이번 패스에서 바뀐 것 / 바뀌지 않은 것

**바뀐 것**: `implementations/go/docs/guide.md`(258줄 단일 파일)를 삭제하고 `implementations/go/docs/architecture/*.md` 21개 문서로 전면 재구성했다. 각 문서는 root 원칙을 설명하고, 가능한 곳은 실제 `examples/` 코드를 근거로 들며, 코드가 아직 원칙을 완전히 따르지 않는 지점은 "알려진 격차"로 명시하고 목표 구현을 코드로 제시했다. `implementations/go/CLAUDE.md`도 NestJS와 동일한 키워드→문서 인덱스 표 구조로 재작성했다.

**바뀌지 않은 것**: `implementations/go/examples/`와 `implementations/go/harness/`는 이번 패스에서 전혀 수정하지 않았다 — 순수 문서화 작업이다. 따라서 아래는 문서에는 정확히 기술되어 있지만 **코드에는 여전히 남아있는 격차**다(각 문서의 "알려진 격차" 섹션에 상세):

1. **aggregate-id.md**: `uuid.NewString()`이 하이픈 포함 형식을 그대로 씀.
2. **domain-events.md**: Outbox 없이 `notification.Service.Notify()`를 동기 호출하는 dual-write 패턴.
3. **testing.md**: Domain/Application 단위 테스트 부재(E2E만 존재).
4. **authentication.md**: `X-User-Id` 헤더를 검증 없이 신뢰.
5. **persistence.md**: 컨텍스트 기반 여러 Repository 트랜잭션 전파 미구현, 마이그레이션 롤백 스크립트 부재.
6. **error-handling.md**: 표준 JSON 에러 응답 스키마(`statusCode`/`code`/`message`/`error`) 미구현, 평문 텍스트만 반환.
7. **observability.md**: `log/slog` 미사용(`log.Printf`만 사용), Correlation ID 전파 미구현.
8. **graceful-shutdown.md**, **config.md**, **container.md**, **secret-manager.md**, **file-storage.md**, **scheduling.md**: 해당 패턴 자체가 `examples/`에 전혀 구현되어 있지 않음 — 문서는 전량 목표 설계.

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

**harness는 순수하게 파일 위치·네이밍만 검사하며, 새로 작성된 `docs/architecture/*.md` 21개 문서가 다루는 주제 중 어느 것도 의미적으로 검증하지 않는다.** 문서화가 두터워진 지금 시점에 다음이 여전히 유효한 간극이다:

- Repository 메서드 네이밍이 root 규칙(`find<Noun>s`/`save<Noun>`/`delete<Noun>`)을 따르는지
- CQRS Handler가 실제로 `Handle(ctx, cmd/query) (result, error)` 시그니처를 갖는지
- 에러가 sentinel error인지, HTTP 상태 코드 매핑이 표준 JSON 스키마를 따르는지
- Soft Delete 컬럼이 실제로 존재하고 조회 시 필터링되는지
- Aggregate ID가 하이픈 제거 32자리 hex 형식인지(현재 `examples/`는 이 규칙을 어기고 있는데도 harness는 통과시킴)
- Domain/Application 단위 테스트 파일의 존재 여부
- Outbox 테이블/Relay의 존재 여부(domain-events.md가 요구하는 핵심 메커니즘)

문서화 패스가 끝났으므로, harness 규칙을 위 항목들로 확장하는 것이 다음 단계로 고려할 만하다(이번 패스 범위 밖).

---

## Go 전용, 대응 root 문서 없음

- **컴파일 타임 interface 충족 검증** — `var _ order.Repository = (*OrderRepository)(nil)` 관용구. TypeScript의 구조적 타이핑이나 NestJS의 DI 토큰 바인딩과 달리, Go는 이 패턴으로 "구현체가 인터페이스를 만족하지 못하면 컴파일 자체가 실패"하는 정적 안전성을 얻는다. root의 어떤 문서도 이런 컴파일 타임 검증 메커니즘을 언급하지 않는다.
- **패키지 단위 캡슐화** — Go에는 인스턴스 단위 `private`이 없다(`tactical-ddd.md` 참고). root 문서들은 이 제약을 전제하지 않는다.

---

## Go 선택 이유

- **표준 라이브러리 우선** — `go.mod`의 직접 의존성은 AWS SDK, `google/uuid`, `lib/pq`, 테스트용 `testify`/`testcontainers`뿐이다. 웹 프레임워크도, ORM도, DI 컨테이너도 쓰지 않는다.
- **interface의 구조적 타이핑이 Repository/Technical Service 추상화에 자연스럽게 들어맞는다.**
- **CQRS의 Handler 패턴이 프레임워크 없이 구조체 + 메서드만으로 표현된다.**
- **정적 컴파일과 명시적 에러 처리(`error` 반환값)가 Domain/Application의 plain error 원칙과 궁합이 좋다.**

# Go 구현 가이드

DDD 기반 Go 백엔드 서비스의 설계/구현 가이드이다.
`internal/{domain,application,infrastructure,interface}/` 4레이어 구조를 따른다.

> **설계 원칙 (프레임워크 무관)** 은 루트의 [CLAUDE.md](../../CLAUDE.md) 및 `../../docs/architecture/`를 참조한다.
> 이 문서는 Go 구현 상세에 집중한다.

## 작업 시 참조할 문서

### 설계 / 구조

| 작업 / 키워드 | 읽을 문서 |
|---------------|----------|
| 전략적 설계, Subdomain, Bounded Context, Context Map, 유비쿼터스 언어 | `../../docs/architecture/strategic-ddd.md` (루트 공용 문서) |
| 프로젝트 구조, 디렉토리 레이아웃, `internal/` 패키지 배치 | `docs/architecture/directory-structure.md` |
| 레이어 역할, Domain / Application / Interface / Infrastructure, 의존 방향 | `docs/architecture/layer-architecture.md` |
| Aggregate Root, Entity, Value Object, Domain Event 모델링, struct + 메서드, 캡슐화 한계 | `docs/architecture/tactical-ddd.md` |
| Repository interface·구현체 분리, 컴파일 타임 interface 검증 | `docs/architecture/repository-pattern.md` |
| Aggregate ID 생성 규칙, 32자리 hex | `docs/architecture/aggregate-id.md` |
| 핵심 설계 원칙 TL;DR, 체크리스트 요약 | `docs/architecture/design-principles.md` |
| DI 컨테이너 없는 의존성 조립, 패키지 = 모듈 경계, 순환 의존 방지 | `docs/architecture/module-pattern.md` |
| 크로스 도메인 호출, Adapter 패턴 구현, 다른 Bounded Context 호출 예시 | `docs/architecture/cross-domain.md` |
| 공유 코드 위치, `internal/common/`, 공용 인프라 배치 | `docs/architecture/shared-modules.md` |
| 새 도메인 추가, 도메인 스캐폴딩 템플릿, Order 예시 | `docs/reference.md` — `scripts/create-domain`으로 `docs/reference.md` 템플릿을 실제 코드로 즉시 생성 가능(아래 "스캐폴딩" 참고) |

### 데이터 / 트랜잭션

| 작업 / 키워드 | 읽을 문서 |
|---------------|----------|
| 트랜잭션 전파, `context.Context`, Unit of Work, Soft Delete, 마이그레이션 | `docs/architecture/persistence.md` |
| Domain Event, 이벤트 수집, Outbox 패턴, dual-write 회피 | `docs/architecture/domain-events.md` |
| CQRS, Command/Query Handler, `Handle(ctx, cmd/query)` 패턴 | `docs/architecture/cqrs-pattern.md` |

### API / Interface

| 작업 / 키워드 | 읽을 문서 |
|---------------|----------|
| REST 엔드포인트, `net/http` 핸들러, DTO | `docs/architecture/api-response.md` |
| 응답 형식, Pagination, `page`/`take` | `docs/architecture/api-response.md` |
| 인증, JWT, Bearer 토큰, 미들웨어 기반 Guard 대체 | `docs/architecture/authentication.md` |
| Middleware 체인, Correlation ID 주입, 요청 검증 | `docs/architecture/cross-cutting-concerns.md` |
| 에러 처리, sentinel error, `errors.Is`, HTTP 상태 코드 매핑 | `docs/architecture/error-handling.md` |
| Rate Limiting, 토큰 버킷, `golang.org/x/time/rate`, 요청 속도 제한 | `docs/architecture/rate-limiting.md` |

### 운영 / 인프라

| 작업 / 키워드 | 읽을 문서 |
|---------------|----------|
| 환경 설정, 환경 변수 검증, fail-fast, 설정 구조체 | `docs/architecture/config.md` |
| Secret 관리, AWS Secrets Manager, TTL 캐시 | `docs/architecture/secret-manager.md` |
| 컨테이너 이미지, Dockerfile, 멀티스테이지 빌드, 단일 정적 바이너리 | `docs/architecture/container.md` |
| Graceful Shutdown, `signal.NotifyContext`, `http.Server.Shutdown` | `docs/architecture/graceful-shutdown.md` |
| 앱 진입점, `main.go`, 의존성 조립 순서, 서버 기동 | `docs/architecture/bootstrap.md` |
| 로컬 개발 환경, docker-compose, LocalStack | `docs/architecture/local-dev.md` |
| Logging, `log/slog`, 구조화 로그, Correlation ID 전파 | `docs/architecture/observability.md` |
| 파일 스토리지, Presigned URL, S3 SDK v2 | `docs/architecture/file-storage.md` |

### 비동기 / Task Queue / Scheduling

| 작업 / 키워드 | 읽을 문서 |
|---------------|----------|
| Scheduling, `time.Ticker`, 배치 작업, Task Queue, Task Outbox | `docs/architecture/scheduling.md` |

### 품질 / 검증

| 작업 / 키워드 | 읽을 문서 |
|---------------|----------|
| Testing, table-driven test, Domain/Application/E2E 3단계, testcontainers-go | `docs/architecture/testing.md` |
| harness 실행, 규칙 목록 | `harness/README.md` |
| harness 설계 원칙(비즈니스 도메인 지식이 아닌 아키텍처 규칙만 평가) | `../../docs/harness.md` (루트 공용 문서) |
| Lint, golangci-lint 실행 방법, 코드 품질 규칙 | 아래 "Lint" 절 참조 |

## 구현 검증

```bash
./harness.sh <projectRoot>
```

FAIL 항목의 `ruleId`와 메시지에 관련 문서 링크가 포함된다. 해당 문서를 열어 수정한다.

## Lint

`examples/`(앱)와 `harness/`(evaluator)는 별도 Go module이지만 `.golangci.yml` 설정 하나를
`implementations/go/`에 두고 공유한다 — golangci-lint는 실행 디렉토리부터 상위로 올라가며
설정 파일을 찾으므로 두 모듈 하위에서 실행해도 같은 설정이 적용된다.

활성화한 linter는 golangci-lint 기본 세트(`errcheck`, `govet`, `ineffassign`, `staticcheck`,
`unused`)에 `unconvert`(불필요한 타입 변환 탐지)와 `gofmt` formatter를 더한 것뿐이다 —
`revive`/`stylecheck` 같은 지나치게 엄격한 규칙 세트는 켜지 않는다(불필요하게 큰 diff를 피하기 위함).

```bash
# 로컬 설치 (https://golangci-lint.run/welcome/install/)
go install github.com/golangci/golangci-lint/v2/cmd/golangci-lint@latest

# 실행 — 두 모듈 각각에서
(cd examples && golangci-lint run ./...)
(cd harness && golangci-lint run ./...)
```

CI(`.github/workflows/go.yml`)는 `golangci/golangci-lint-action`으로 두 모듈을 각각 검사하며,
위반이 있으면 빌드가 실패한다.

## 스캐폴딩 — 새 도메인 생성기

`docs/reference.md`의 "실전 구현 템플릿"(Order 예시)을 실제 코드로 만들어 harness 전체를
통과시킨 뒤, 도메인 이름만 파라미터화해 재사용 가능하게 일반화한 Go 프로그램이다
(`scripts/create-domain/`). Aggregate(단일 Status 필드 + PENDING/ACTIVE/CANCELLED) +
CQRS Command/Query Handler + 도메인 이벤트 1종 + Repository(도메인 인터페이스 +
infrastructure 구현체) + HTTP Handler/DTO + 마이그레이션까지 한 번에 생성한다.

`scripts/create-domain/`는 `examples`/`harness`와 마찬가지로 독립된 Go module(자체 go.mod)이다
— `implementations/go/`에는 이들을 묶는 go.mod/go.work가 없으므로, 반드시 `scripts/create-domain/`
안에서 `go run .`으로 실행한다(`go run ./scripts/create-domain`을 `implementations/go/`에서
실행하면 "go.mod file not found"로 실패한다).

```bash
cd scripts/create-domain

# 기본: examples/internal/... 아래 생성, main.go/router.go는 건드리지 않고 붙여넣을
# 내용만 콘솔에 출력
go run . Coupon

# --wire를 주면 cmd/server/main.go(저장소 조립 + outbox.Poller/outbox.Consumer가 공유하는 handlers map 등록)와
# internal/interface/http/router.go(Handler 조립 + 라우트 등록)까지 자동 삽입
go run . Coupon --wire

# 다른 프로젝트(이 저장소를 템플릿으로 복제한 프로젝트 등)에 생성하려면 --out으로 지정
go run . Coupon --out /path/to/other-project --wire
```

NestJS는 도메인마다 전용 Relay/Consumer를 두지 않듯, Go도 도메인마다 전용 타입을 두지
않는다 — `main.go`가 조립하는 **단일 공유** `map[string]outbox.Handler`에 모든 도메인이
이벤트를 함께 등록하고, 이 map을 `outbox.Poller`(Outbox → SQS 발행,
`internal/infrastructure/outbox/poller.go`)와 `outbox.Consumer`(SQS → Handler 실행,
`internal/infrastructure/outbox/consumer.go`)가 나눠 쓴다. Command Handler는 이 map을
전혀 참조하지 않는다 — 저장 후 곧바로 반환한다(동기 드레인 금지, domain-events.md). 그래서
`--wire`는 `main.go`(및 `main.go`와 똑같은 모양으로 의존성을 다시 조립하는 e2e 테스트가
있다면 그 파일까지)와 `router.go` 양쪽을 함께 고친다.

생성 직후 `./harness.sh <projectRoot>`로 검증한다 — Account/Card와 무관한 새 도메인
(Coupon, LoyaltyCategory 등 다단어/불규칙 복수형 포함)으로 실제 테스트해 harness
전체 통과, `go build`/`go vet`/`golangci-lint run` 무경고를 확인했다. 나이브 복수형
규칙(+s/+es/y→ies)을 쓰므로, 불규칙 복수형 도메인이면 테이블명·REST 경로(`<domains>Lower`)
등 생성된 이름을 수동으로 다듬어야 할 수 있다. 공유 `outbox.Writer`(SaveAll)는 현재
`account.DomainEvent` 슬라이스 타입에 고정돼 있어 다른 도메인이 그대로 재사용할 수
없다는 알려진 격차가 있다 — 생성된 Repository는 이를 우회해 같은 트랜잭션 안에서 Outbox
행을 직접 적재한다(생성된 `<domain>_repository.go`의 주석 참고). 생성되는 것은 구조적
스켈레톤(빈 CRUD형 시작점)이라, 실제 비즈니스 규칙·에러 메시지·필드는 생성 후 직접
채워 넣는다.

## 예시 코드 (Account 도메인 전체)

`examples/` 디렉토리에 Account 도메인 전체 구현 예시가 있다(계좌 개설/입출금/정지/재개/종료 + 이메일 알림).
새 도메인 작업 시 이를 템플릿으로 사용한다 (도메인명만 변경).

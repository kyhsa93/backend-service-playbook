# 공유 코드 구조 (Go)

Go 전용 문서 — root에는 대응 문서가 없다. NestJS는 `src/common/`, `src/database/`, `src/outbox/`, `src/auth/`를 `@Global` 모듈로 선언해 여러 도메인 모듈에 주입한다. Go에는 "전역 모듈"이라는 개념이 없다 — **공유 코드는 그냥 `internal/` 아래의 평범한 패키지이고, "공유"는 `main.go`가 같은 인스턴스를 여러 도메인의 생성자에 전달하는 것으로 이루어진다.**

---

## 현재 상태 — `common/`/`config/`/`infrastructure/auth/`/`infrastructure/outbox/` 모두 존재

`internal/` 트리를 확인하면(directory-structure.md 참고), Account/Card/Credential 세 도메인이 함께 쓰는 공유 패키지가 이미 여러 개 있다:

- **`internal/common/`**(`id.go`) — `common.NewID()`(UUID v4 하이픈 제거) 유틸([aggregate-id.md](aggregate-id.md) 참고).
- **`internal/config/`**(`database.go`/`jwt.go`/`rate_limit.go`/`secret_service.go`) — 관심사별 설정 로딩/검증([config.md](config.md) 참고).
- **`internal/infrastructure/outbox/`**(`Writer`/`Poller`/`Consumer`) — 알림 발송이 유실되면 안 되는 부가효과여서 dual-write 대신 Outbox 패턴이 필요해졌다([domain-events.md](domain-events.md) 참고).
- **`internal/infrastructure/auth/`**(`bcrypt_password_hasher.go`/`jwt_service.go`), **`internal/infrastructure/secret/`**(`service.go`) — 인증/Secrets Manager Technical Service 구현체([authentication.md](authentication.md), [secret-manager.md](secret-manager.md) 참고).

이 문서는 그 배치를 도메인이 더 늘어났을 때 어디로 나뉘는지 정리한다.

---

## 실제 구조

```
internal/
  common/                          # 프레임워크 무의존 순수 유틸 — 어떤 도메인도 소유하지 않는다
    id.go                          # common.NewID() — aggregate-id.md

  config/                          # 관심사별 설정 로딩/검증 — config.md
    database.go
    jwt.go
    rate_limit.go
    secret_service.go

  infrastructure/
    persistence/
      account_repository.go        # Account 전용
      card_repository.go           # Card 전용
      credential_repository.go     # Credential 전용
    auth/                          # 인증 Technical Service 구현체 — authentication.md
      bcrypt_password_hasher.go
      jwt_service.go
    secret/                        # Secrets Manager 접근 구현체 — secret-manager.md
      service.go
    notification/
      service.go                   # Account 전용 알림 구현체
    outbox/                        # OutboxWriter, OutboxPoller/OutboxConsumer(domain-events.md), 도메인이 늘어나도 공유
      writer.go
      relay.go

  interface/
    http/
      middleware/                  # 모든 도메인의 라우터가 공유하는 미들웨어 — cross-cutting-concerns.md
        correlation_id_middleware.go
        auth_middleware.go
        rate_limit_middleware.go
      health_handler.go            # 도메인에 속하지 않는 liveness/readiness — graceful-shutdown.md

  domain/
    account/                       # Account Bounded Context
    card/                          # Card Bounded Context
    credential/                    # 인증/가입 Aggregate

  application/
    command/                       # 세 도메인의 핸들러를 평평한 구조로 함께 담는다 (도메인이 더 늘면 command/<domain>/ 검토)
    query/
```

- **`internal/common/`** — 어떤 도메인도 참조할 수 있는 프레임워크 무의존 순수 함수(ID 생성 등). Domain 레이어에서 import해도 원칙 2(프레임워크 무의존)를 어기지 않는다 — `common` 패키지 자체가 표준 라이브러리 수준의 순수 함수만 담기 때문이다.
- **`internal/interface/http/middleware/`** — 이미 [cross-cutting-concerns.md](cross-cutting-concerns.md)가 위치를 정해 둔 HTTP 전용 공유 코드. 도메인마다 반복 구현하지 않고 하나의 체인을 모든 라우터에 적용한다.
- **`internal/infrastructure/outbox/`** — Account/Card 두 도메인의 Repository가 같은 `outbox.Writer` 인스턴스를, `main.go`가 조립하는 단일 `outbox.Poller`/`outbox.Consumer`가 같은 공유 `map[string]outbox.Handler`를 공유한다([domain-events.md](domain-events.md) 참고). 여러 도메인을 하나의 DB 트랜잭션으로 묶는 `internal/infrastructure/persistence/tx.go`는 그런 시나리오가 아직 없어서 만들지 않았다([persistence.md](persistence.md) 참고).
- **도메인 전용 코드는 공유 패키지로 옮기지 않는다** — `account_repository.go`처럼 특정 도메인만 쓰는 구현체는 그 도메인의 `infrastructure/<concern>/` 하위에 그대로 둔다. "공유"는 두 도메인 이상이 실제로 같은 코드를 필요로 할 때만 성립한다(YAGNI).

---

## "공유"는 선언이 아니라 같은 인스턴스를 전달하는 것

NestJS는 `DatabaseModule`을 `@Global()`로 선언하면 모든 모듈이 별도 `imports` 없이 `DataSource`를 주입받을 수 있다. Go는 그런 전역 스코프 선언이 없다 — 공유는 순전히 **`main.go`가 같은 값을 여러 생성자에 인자로 넘기는 것**으로 이루어진다.

```go
// main.go — db 커넥션 풀 하나를 두 도메인의 Infrastructure에 공유
db, err := sql.Open("postgres", cfg.URL)
// ...

accountRepo := persistence.NewAccountRepository(db)  // Account가 db를 공유
userRepo := userpersistence.NewRepository(db)        // User도 같은 db를 공유

logger := slog.New(slog.NewJSONHandler(os.Stdout, nil))
loggingMW := middleware.RequestLogging(logger)       // 미들웨어도 한 번만 만들어 여러 라우터에 재사용
```

"전역"이라는 특별한 스코프는 없다 — `db`, `logger` 같은 변수가 `main()` 함수 스코프에 있고, 그 값을 필요로 하는 모든 생성자에 인자로 넘겼을 뿐이다. NestJS의 `@Global` 데코레이터가 하던 일을 Go에서는 "그 변수를 아는 함수에게 인자로 넘긴다"는 지극히 평범한 방식으로 대신한다. 상세는 [module-pattern.md](module-pattern.md)와 [bootstrap.md](bootstrap.md) 참고.

---

## 원칙

- **공유 패키지는 실제로 두 도메인 이상이 필요로 할 때만 만든다** — 미리 빈 공유 패키지를 만들어두지 않는다. `internal/common/`/`internal/config/`는 Account/Card/Credential이 실제로 공유하게 되어 만들어졌고, `internal/infrastructure/persistence/tx.go`(공유 트랜잭션 헬퍼)는 아직 그런 시나리오가 없어 만들지 않았다.
- **`internal/common/`은 프레임워크 무의존 순수 함수만** 담는다 — DB, HTTP 등 특정 기술에 의존하는 코드를 넣지 않는다(그런 코드는 `internal/infrastructure/<concern>/`로 간다).
- **HTTP 전용 공유 코드는 `internal/interface/http/middleware/`**에 모은다 — 도메인별 Handler에 중복 구현하지 않는다.
- **공유는 선언이 아니라 배선**이다 — `main.go`에서 같은 인스턴스를 여러 생성자에 전달하는 것이 "전역 모듈"을 대신한다.

---

### 관련 문서

- [directory-structure.md](directory-structure.md) — 공용 인프라 디렉토리 현황(`database/`만 아직 없는 이유)
- [aggregate-id.md](aggregate-id.md) — `internal/common/id.go` 실제 코드
- [cross-cutting-concerns.md](cross-cutting-concerns.md) — `internal/interface/http/middleware/` 공유 미들웨어
- [module-pattern.md](module-pattern.md) — 패키지 경계와 배선 메커니즘 전반
- [bootstrap.md](bootstrap.md) — `main.go`가 공유 인스턴스를 만들고 전달하는 실제 순서

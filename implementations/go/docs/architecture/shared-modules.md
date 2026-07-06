# 공유 코드 구조 (Go)

Go 전용 문서 — root에는 대응 문서가 없다. NestJS는 `src/common/`, `src/database/`, `src/outbox/`, `src/auth/`를 `@Global` 모듈로 선언해 여러 도메인 모듈에 주입한다. Go에는 "전역 모듈"이라는 개념이 없다 — **공유 코드는 그냥 `internal/` 아래의 평범한 패키지이고, "공유"는 `main.go`가 같은 인스턴스를 여러 도메인의 생성자에 전달하는 것으로 이루어진다.**

---

## 현재 상태 — `examples/`에는 아직 전용 공유 패키지가 없다

`internal/` 트리를 확인하면(directory-structure.md 참고), Account 도메인 하나뿐이라 아직 도메인에 속하지 않는 공유 패키지 자체가 없다. 다만 이 저장소의 다른 두 문서가 이미 **미래에 만들어질 공유 패키지의 이름과 위치를 목표 코드로 못박아 두었다**:

- [aggregate-id.md](aggregate-id.md) — `internal/common/id.go`에 `common.NewID()`(UUID v4 하이픈 제거) 유틸을 둔다고 명시.
- [cross-cutting-concerns.md](cross-cutting-concerns.md) — Correlation ID 미들웨어가 `github.com/example/account-service/internal/common`을 import해 `common.NewID()`를 호출하는 목표 코드를 제시.

즉 **`internal/common/`이 이 저장소가 이미 합의한 "공유 패키지"의 이름**이다. 이 문서는 그 합의를 확장해서, 도메인이 하나 더 늘어났을 때 공유 코드 전체가 어디로 나뉘는지 정리한다.

---

## 목표 구조 — 도메인이 둘 이상이 될 때

```
internal/
  common/                          # 프레임워크 무의존 순수 유틸 — 어떤 도메인도 소유하지 않는다
    id.go                          # common.NewID() — aggregate-id.md
    errors.go                      # (필요 시) 도메인 공통 에러 헬퍼

  infrastructure/
    persistence/
      tx.go                        # (필요 시) 여러 Repository를 묶는 공유 트랜잭션 헬퍼 — persistence.md
      account_repository.go        # Account 전용
      user_repository.go           # User 전용 (가상)
    notification/
      service.go                   # Account 전용 알림 구현체
    outbox/                        # (필요 시) OutboxWriter, OutboxRelay — domain-events.md
      writer.go
      relay.go

  interface/
    http/
      middleware/                  # 모든 도메인의 라우터가 공유하는 미들웨어 — cross-cutting-concerns.md
        correlation_middleware.go
        auth_middleware.go
        logging_middleware.go
      health_handler.go            # 도메인에 속하지 않는 liveness/readiness — graceful-shutdown.md

  domain/
    account/                       # Account Bounded Context
    user/                          # User Bounded Context (가상)

  application/
    command/                       # 여러 도메인이 생기면 command/<domain>/ 로 세분화 검토
    query/
```

- **`internal/common/`** — 어떤 도메인도 참조할 수 있는 프레임워크 무의존 순수 함수(ID 생성 등). Domain 레이어에서 import해도 원칙 2(프레임워크 무의존)를 어기지 않는다 — `common` 패키지 자체가 표준 라이브러리 수준의 순수 함수만 담기 때문이다.
- **`internal/interface/http/middleware/`** — 이미 [cross-cutting-concerns.md](cross-cutting-concerns.md)가 위치를 정해 둔 HTTP 전용 공유 코드. 도메인마다 반복 구현하지 않고 하나의 체인을 모든 라우터에 적용한다.
- **`internal/infrastructure/outbox/`**, **`internal/infrastructure/persistence/tx.go`** — 여러 도메인이 하나의 트랜잭션/Outbox 흐름을 공유해야 할 때 필요해지는 인프라. 아직 이 저장소에는 없다([domain-events.md](domain-events.md), [persistence.md](persistence.md) 참고).
- **도메인 전용 코드는 공유 패키지로 옮기지 않는다** — `account_repository.go`처럼 특정 도메인만 쓰는 구현체는 그 도메인의 `infrastructure/<concern>/` 하위에 그대로 둔다. "공유"는 두 도메인 이상이 실제로 같은 코드를 필요로 할 때만 성립한다(YAGNI — directory-structure.md의 "공용 인프라를 아직 추가하지 않은 이유"와 같은 원칙).

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

- **공유 패키지는 실제로 두 도메인 이상이 필요로 할 때만 만든다** — 미리 빈 `internal/common/`을 만들어두지 않는다(현재 이 저장소가 그렇다: 목표는 정해져 있지만 아직 만들지 않았다).
- **`internal/common/`은 프레임워크 무의존 순수 함수만** 담는다 — DB, HTTP 등 특정 기술에 의존하는 코드를 넣지 않는다(그런 코드는 `internal/infrastructure/<concern>/`로 간다).
- **HTTP 전용 공유 코드는 `internal/interface/http/middleware/`**에 모은다 — 도메인별 Handler에 중복 구현하지 않는다.
- **공유는 선언이 아니라 배선**이다 — `main.go`에서 같은 인스턴스를 여러 생성자에 전달하는 것이 "전역 모듈"을 대신한다.

---

### 관련 문서

- [directory-structure.md](directory-structure.md) — 공용 인프라 디렉토리(`common/`, `database/`, `outbox/`)가 아직 없는 이유
- [aggregate-id.md](aggregate-id.md) — `internal/common/id.go` 목표 코드
- [cross-cutting-concerns.md](cross-cutting-concerns.md) — `internal/interface/http/middleware/` 공유 미들웨어
- [module-pattern.md](module-pattern.md) — 패키지 경계와 배선 메커니즘 전반
- [bootstrap.md](bootstrap.md) — `main.go`가 공유 인스턴스를 만들고 전달하는 실제 순서

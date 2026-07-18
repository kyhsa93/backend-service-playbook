# 횡단 관심사 (Go)

원칙은 루트 [cross-cutting-concerns.md](../../../../docs/architecture/cross-cutting-concerns.md)를 따른다: 인증, 로깅, 입력 검증, Correlation ID는 각자의 역할에 맞는 파이프라인 단계에서 처리하고 역할을 혼용하지 않는다. Go에는 NestJS의 Guard/Pipe/Interceptor 같은 전용 데코레이터 계층이 없다 — `net/http`의 **미들웨어 체인**(`func(http.Handler) http.Handler`를 감싸는 함수들)으로 동일한 역할을 구현한다.

---

## 요청 파이프라인

```
요청 → [1. Correlation ID 미들웨어] → [2. 로깅 미들웨어] → [3. Rate Limit] → [4. 인증 미들웨어] → [5. 입력 검증] → [6. Handler] → 응답
```

Go 미들웨어는 `http.Handler`를 감싸는 함수를 연쇄적으로 합성(compose)하는 방식으로 동작한다. 각 미들웨어가 정확히 하나의 관심사만 담당하도록 나눈다.

| 단계 | 역할 | Go 구현 위치 |
|------|------|------|
| 1. Correlation ID | 모든 요청에 추적 ID 주입 | `interface/http/middleware/correlation_id_middleware.go` |
| 2. 응답 로깅 | 요청 메서드/경로/상태 코드/소요 시간 로깅 | `interface/http/middleware/logging_middleware.go`(`RequestLogging`) |
| 3. Rate Limit | 초당 요청 수 제한 | `interface/http/middleware/rate_limit_middleware.go` ([rate-limiting.md](rate-limiting.md)) |
| 4. 인증 | 요청 허용/거부, `context`에 사용자 정보 주입 | `interface/http/middleware/auth_middleware.go` ([authentication.md](authentication.md)) |
| 5. 입력 검증 | JSON 디코딩, 필수 필드 확인 | Handler 진입부 (Go는 별도 Pipe 계층이 없음 — 아래 참조) |
| 6. Handler | Command/Query Handler 호출 | `interface/http/account_handler.go` |

`RequestLogging`이 `RateLimit`보다 바깥쪽(먼저 실행)에 있는 이유는, 429로 거부된 요청도 로그에 남겨야 하기 때문이다 — `router.go`의 실제 체인은 `CorrelationID(RequestLogging(mux))`이고, `mux`가 다시 `RateLimit(limiter)(limited)`를 라우팅한다.

---

## 미들웨어 합성 패턴 — 중첩 함수 호출

Go 표준 라이브러리에는 미들웨어 체이닝 헬퍼가 없다. 이 저장소는 별도의 `Chain()` 헬퍼를 두지 않고, `router.go`에서 미들웨어를 직접 중첩 호출하는 방식을 쓴다(`A(B(h))` 형태):

```go
// internal/interface/http/router.go — 실제 코드(요약)
limited := http.NewServeMux()
limited.Handle("/accounts", middleware.RequireAuth(jwtService)(protected)) // 4. 인증
limited.HandleFunc("POST /auth/sign-in", authHTTP.SignIn)                  // 인증 불필요

mux := http.NewServeMux()
mux.Handle("/", middleware.RateLimit(limiter)(limited)) // 3. Rate Limit
mux.HandleFunc("GET /health/live", healthHandler.Live)  // Rate Limit/인증 모두 적용하지 않음

return middleware.CorrelationID(middleware.RequestLogging(mux)), healthHandler // 1·2. 전처리(가장 바깥)
```

미들웨어가 몇 개뿐이라 이 정도 중첩으로도 가독성에 문제가 없다 — 개수가 늘어나면 `Chain(h, A, B, C)` 형태의 합성 헬퍼 도입을 검토한다.

---

## Correlation ID 주입 (전처리 단계)

```go
// internal/interface/http/middleware/correlation_id_middleware.go — 실제 코드
package middleware

import (
	"context"
	"net/http"

	"github.com/google/uuid"

	"github.com/example/account-service/internal/common"
)

func CorrelationID(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		correlationID := r.Header.Get("X-Correlation-Id")
		if correlationID == "" {
			correlationID = uuid.NewString() // 클라이언트가 안 보내면 서버가 생성
		}
		ctx := common.WithCorrelationID(r.Context(), correlationID)
		w.Header().Set("X-Correlation-Id", correlationID)
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

func CorrelationIDFromContext(ctx context.Context) string {
	return common.CorrelationIDFromContext(ctx)
}
```

context 값을 담는 키(`correlationIDKey`)는 `internal/common`(프레임워크 무의존, 어느 레이어도 참조할 수 있는 패키지)에 있다 — `internal/infrastructure/logging.CorrelationHandler`(아래 [observability.md](observability.md) 참고)도 같은 키로 읽어야 하기 때문이다.

Node.js의 `AsyncLocalStorage`가 하던 역할을 Go에서는 **`context.Context` 값 전파**가 대신한다 — Go는 함수 호출마다 `ctx`를 명시적으로 전달하는 언어이므로, 숨겨진 저장소(ALS) 대신 인자로 전달되는 `context.Context`가 Correlation ID, 취소 신호, 데드라인을 함께 나른다. 자세한 사용은 [observability.md](observability.md) 참조.

---

## 인증 (미들웨어 단계)

토큰 검증과 사용자 정보 추출은 Handler 진입 전에 끝낸다. 상세 구현은 [authentication.md](authentication.md) 참조.

---

## 입력 검증 — Go에는 별도 Pipe 계층이 없다

NestJS의 `ValidationPipe` + `class-validator` 데코레이터 같은 자동 검증 계층이 Go 표준 라이브러리에는 없다. 이 저장소는 **Handler 진입부에서 명시적으로 검증**한다.

```go
// internal/interface/http/account_handler.go
func (h *AccountHandler) CreateAccount(w http.ResponseWriter, r *http.Request) {
	var body CreateAccountRequest
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest) // 형식 검증 실패 → 400
		return
	}
	if !isValidEmail(body.Email) {
		http.Error(w, "email must be a valid, non-empty email address", http.StatusBadRequest)
		return
	}
	// 여기까지 통과해야 Handler(비즈니스 로직)에 도달한다
	a, err := h.createAccount.Handle(r.Context(), command.CreateAccountCommand{ /* ... */ })
	// ...
}
```

- **형식적 검증**(JSON 파싱 실패, 이메일 형식)은 root 원칙과 동일하게 파이프라인 초기 — 이 저장소에서는 Handler 최상단 — 에서 400으로 즉시 차단한다.
- **비즈니스 규칙 검증**(이미 정지된 계좌에 입금 시도 등)은 Domain 레이어(`Account.Deposit()` 내부)에서 처리한다. `writeAccountError`가 이를 구분해 상태 코드를 매핑한다([error-handling.md](error-handling.md) 참조).
- 검증 항목이 많아지면 `internal/interface/http/validate.go`처럼 검증 전용 파일로 추출해 Handler 함수를 짧게 유지할 수 있다. `go-playground/validator` 같은 태그 기반 검증 라이브러리를 쓰면 NestJS의 `class-validator`와 유사한 선언적 검증을 얻을 수 있지만, 이 저장소는 표준 라이브러리만으로 처리하는 것을 우선했다(`isValidEmail`이 그 예).

---

## HTTP 요청 로깅 (응답 후처리 단계)

이 저장소는 개별 도메인 이벤트 로깅(`notification/service.go`의 발송 성공, `outbox/relay.go`의 처리 실패 — [observability.md](observability.md) 참고)뿐 아니라, 모든 요청에 대해 일관되게 메서드/경로/상태 코드/소요 시간을 남기는 응답 로깅 미들웨어도 갖추고 있다:

```go
// internal/interface/http/middleware/logging_middleware.go — 실제 코드(요약)
package middleware

import (
	"log/slog"
	"net/http"
	"time"
)

// statusRecorder는 http.ResponseWriter를 감싸 핸들러가 실제로 기록한 상태 코드를 관찰한다.
type statusRecorder struct {
	http.ResponseWriter
	status      int
	wroteHeader bool
}

func (rec *statusRecorder) WriteHeader(status int) {
	rec.status = status
	rec.wroteHeader = true
	rec.ResponseWriter.WriteHeader(status)
}

func RequestLogging(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		rec := &statusRecorder{ResponseWriter: w}
		next.ServeHTTP(rec, r)
		slog.InfoContext(r.Context(), "http request handled",
			"method", r.Method,
			"path", r.URL.Path,
			"status", rec.status,
			"duration_ms", time.Since(start).Milliseconds(),
		)
	})
}
```

correlation_id 필드를 여기서 직접 넘기지 않는 이유는, `main.go`가 `slog` 기본 로거에 씌운 `internal/infrastructure/logging.CorrelationHandler`가 ctx에 실린 값을 모든 로그 레코드에 자동으로 추가하기 때문이다([observability.md](observability.md) 참고) — `RequestLogging`은 `CorrelationID`보다 안쪽(더 나중에 실행되도록)에 등록해야 그 ctx를 물려받는다(`router.go`의 `CorrelationID(RequestLogging(mux))`).

Handler 내부에서 개별적으로 요청 로그를 남기지 않는다 — 로깅은 미들웨어 하나가 모든 라우트에 대해 일관되게 수행한다. 구조화 로깅 상세는 [observability.md](observability.md) 참조.

---

## Domain 레이어에서 횡단 관심사 사용 금지

```go
// 금지 — Domain 레이어에서 로거/HTTP 개념 사용
package account

import "log/slog" // ← 금지

func (a *Account) Cancel(reason string) error {
	slog.Info("계좌 취소") // ← 금지 — Domain은 어떤 로깅 프레임워크도 몰라야 한다
	// ...
}
```

`internal/domain/account/` 패키지의 실제 코드는 `errors`, `time` 등 표준 라이브러리 기본 패키지만 import한다 — `log`, `net/http`, `context`조차 import하지 않는다. Domain 메서드는 값을 받아 값을 반환하거나 에러를 반환할 뿐, 어떤 횡단 관심사에도 관여하지 않는다.

---

## 원칙

- **역할에 맞는 미들웨어를 사용**한다: 인증/로깅/Correlation ID를 각각 별도 미들웨어 함수로 분리한다.
- **미들웨어는 라우트 그룹에 적용**한다: 개별 핸들러 안에서 재현하지 않는다.
- **Handler는 순수하게**: Command/Query Handler 호출과 요청/응답 변환만 담당한다.
- **`context.Context`로 전파**: Correlation ID, 인증 정보 등은 context 값으로 다음 레이어에 전달한다.

---

### 관련 문서

- [authentication.md](authentication.md) — 인증 미들웨어 상세
- [observability.md](observability.md) — 구조화 로깅, Correlation ID 전파
- [error-handling.md](error-handling.md) — 에러 → HTTP 상태 코드 변환 위치

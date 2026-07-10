# 횡단 관심사 (Go)

원칙은 루트 [cross-cutting-concerns.md](../../../../docs/architecture/cross-cutting-concerns.md)를 따른다: 인증, 로깅, 입력 검증, Correlation ID는 각자의 역할에 맞는 파이프라인 단계에서 처리하고 역할을 혼용하지 않는다. Go에는 NestJS의 Guard/Pipe/Interceptor 같은 전용 데코레이터 계층이 없다 — `net/http`의 **미들웨어 체인**(`func(http.Handler) http.Handler`를 감싸는 함수들)으로 동일한 역할을 구현한다.

---

## 요청 파이프라인

```
요청 → [1. Correlation ID 미들웨어] → [2. 인증 미들웨어] → [3. 입력 검증] → [4. Handler] → [5. 로깅 미들웨어] → 응답
```

Go 미들웨어는 `http.Handler`를 감싸는 함수를 연쇄적으로 합성(compose)하는 방식으로 동작한다. 각 미들웨어가 정확히 하나의 관심사만 담당하도록 나눈다.

| 단계 | 역할 | Go 구현 위치 |
|------|------|------|
| 1. Correlation ID | 모든 요청에 추적 ID 주입 | `interface/http/middleware/correlation_id_middleware.go` |
| 2. 인증 | 요청 허용/거부, `context`에 사용자 정보 주입 | `interface/http/middleware/auth_middleware.go` ([authentication.md](authentication.md)) |
| 3. 입력 검증 | JSON 디코딩, 필수 필드 확인 | Handler 진입부 (Go는 별도 Pipe 계층이 없음 — 아래 참조) |
| 4. Handler | Command/Query Handler 호출 | `interface/http/account_handler.go` |
| 5. 응답 로깅 | 요청 메서드/경로/소요 시간 로깅 | 아직 미구현 — 개별 이벤트 로깅([observability.md](observability.md))만 있다 |

---

## 미들웨어 합성 패턴 — 중첩 함수 호출

Go 표준 라이브러리에는 미들웨어 체이닝 헬퍼가 없다. 이 저장소는 별도의 `Chain()` 헬퍼를 두지 않고, `router.go`에서 미들웨어를 직접 중첩 호출하는 방식을 쓴다(`A(B(h))` 형태):

```go
// internal/interface/http/router.go — 실제 코드(요약)
mux := http.NewServeMux()
mux.Handle("/accounts", middleware.RequireAuth(jwtService)(protected)) // 2. 인증
mux.HandleFunc("POST /auth/sign-in", authHTTP.SignIn)                 // 인증 불필요
return middleware.CorrelationID(mux)                                   // 1. 전처리(가장 바깥)
```

미들웨어가 2개뿐이라 이 정도 중첩으로도 가독성에 문제가 없다 — 개수가 늘어나면 이 문서 이전 버전이 제시했던 `Chain(h, A, B, C)` 형태의 합성 헬퍼 도입을 검토한다.

---

## Correlation ID 주입 (전처리 단계)

```go
// internal/interface/http/middleware/correlation_id_middleware.go — 실제 코드
package middleware

import (
	"context"
	"net/http"

	"github.com/google/uuid"
)

type correlationIDKey struct{}

func CorrelationID(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		correlationID := r.Header.Get("X-Correlation-Id")
		if correlationID == "" {
			correlationID = uuid.NewString() // 클라이언트가 안 보내면 서버가 생성
		}
		ctx := context.WithValue(r.Context(), correlationIDKey{}, correlationID)
		w.Header().Set("X-Correlation-Id", correlationID)
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

func CorrelationIDFromContext(ctx context.Context) string {
	if id, ok := ctx.Value(correlationIDKey{}).(string); ok {
		return id
	}
	return ""
}
```

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

## HTTP 요청 로깅 (응답 후처리 단계) — 아직 미구현

이 저장소는 개별 도메인 이벤트 로깅(`notification/service.go`의 발송 성공, `outbox/relay.go`의 처리 실패 — [observability.md](observability.md) 참고)은 갖췄지만, 모든 요청에 대해 일관되게 메서드/경로/소요 시간을 남기는 별도의 응답 로깅 미들웨어는 아직 없다. 추가한다면 아래 형태가 된다:

```go
// internal/interface/http/middleware/logging_middleware.go — 제안, 현재 없음
package middleware

import (
	"log/slog"
	"net/http"
	"time"
)

func RequestLogging(logger *slog.Logger) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			start := time.Now()
			next.ServeHTTP(w, r)
			logger.Info("http_request",
				"method", r.Method,
				"path", r.URL.Path,
				"duration_ms", time.Since(start).Milliseconds(),
				"correlation_id", CorrelationIDFromContext(r.Context()),
			)
		})
	}
}
```

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

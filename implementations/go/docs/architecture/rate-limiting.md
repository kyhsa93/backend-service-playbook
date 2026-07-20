# Rate Limiting (Go)

Go 전용 문서 — root에는 대응 문서가 없다. Go 표준 라이브러리에는 NestJS의 `@nestjs/throttler` 같은 내장 rate limiting이 없다 — 이 저장소가 채택 중인 최소 의존성 원칙과 가장 잘 맞는 선택은 Go 팀이 직접 관리하는 `golang.org/x/time/rate`(토큰 버킷)다.

## 현재 구현

`go.mod`에 `golang.org/x/time`이 추가되었고, `internal/interface/http/middleware/rate_limit_middleware.go`의 `RateLimit` 미들웨어가 `router.go`에서 `/accounts`·`/auth/sign-in` 등 모든 API 라우트에 전역으로 적용되어 있다. `internal/config/rate_limit.go`의 `RateLimitConfig`가 `RATE_LIMIT_RPS`/`RATE_LIMIT_BURST` 환경 변수(기본값: 초당 100개, burst 20개)로 임계값을 관리하고, `cmd/server/main.go`가 이 값으로 `rate.Limiter`를 조립해 `NewRouter`에 주입한다. `/health/live`·`/health/ready`는 라우트 등록 시 애초에 rate limit 미들웨어로 감싸지 않는 방식으로 제외했다.

---

## 의존성

```
go get golang.org/x/time/rate
```

`x/time`은 Go 팀이 관리하는 준표준 라이브러리다 — 이 저장소가 지금 쓰는 `github.com/google/uuid`, `github.com/lib/pq`와 같은 급의 "최소하고 신뢰할 수 있는 서드파티"로 취급한다.

---

## 전역 미들웨어 — 토큰 버킷

```go
// internal/interface/http/middleware/rate_limit_middleware.go — 실제 코드
package middleware

import (
	"encoding/json"
	"log/slog"
	"net/http"

	"golang.org/x/time/rate"
)

// rateLimitErrorResponse는 root docs/architecture/error-handling.md가 요구하는
// {statusCode, code, message, error} 표준 JSON 에러 응답과 같은 필드 구성을 갖는다.
type rateLimitErrorResponse struct {
	StatusCode int    `json:"statusCode"`
	Code       string `json:"code"`
	Message    string `json:"message"`
	Error      string `json:"error"`
}

// RateLimit은 전체 서버에 걸쳐 초당 요청 수를 제한하는 토큰 버킷 미들웨어다.
// limiter 하나를 모든 요청이 공유하므로, 특정 클라이언트가 아니라 서버 전체의 처리량을 제한한다.
func RateLimit(limiter *rate.Limiter) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if !limiter.Allow() {
				w.Header().Set("Retry-After", "1")
				w.Header().Set("Content-Type", "application/json")
				w.WriteHeader(http.StatusTooManyRequests)
				body := rateLimitErrorResponse{
					StatusCode: http.StatusTooManyRequests,
					Code:       "RATE_LIMIT_EXCEEDED",
					Message:    "too many requests",
					Error:      http.StatusText(http.StatusTooManyRequests),
				}
				if err := json.NewEncoder(w).Encode(body); err != nil {
					slog.ErrorContext(r.Context(), "failed to encode rate limit error response", "error", err)
				}
				return
			}
			next.ServeHTTP(w, r)
		})
	}
}
```

`rateLimitErrorResponse`가 `internal/interface/http`의 `ErrorResponse`를 그대로 import하지 않는 이유는, `middleware` 패키지가 그 패키지를 import하면 `router.go`(`interface/http`)가 `middleware`를 import하는 것과 순환 참조가 되기 때문이다 — 스키마만 동일하게 복제해 둔다.

`rate.NewLimiter(r, b)`의 `r`은 초당 채워지는 토큰 수(평균 처리율), `b`는 버킷의 최대 용량(순간 burst 허용치)이다. `limiter.Allow()`는 토큰이 있으면 하나 소비하고 `true`, 없으면 즉시 `false`를 반환한다 — 블로킹하지 않는다.

`limiter *rate.Limiter`는 `NewRouter`가 파라미터로 주입받는다 — [cross-cutting-concerns.md](cross-cutting-concerns.md)가 명시하듯 이 저장소는 별도의 `Chain()` 헬퍼 없이 미들웨어를 직접 중첩 호출하는 방식(`A(B(h))`)을 쓰므로, `router.go`가 `RateLimit(limiter)(mux)` 형태로 직접 감싼다.

```go
// internal/config/rate_limit.go — 실제 코드
type RateLimitConfig struct {
	RequestsPerSecond float64 // 초당 평균 허용 요청 수
	Burst             int     // 순간적으로 허용하는 burst 크기
}

func LoadRateLimitConfig() RateLimitConfig {
	cfg := RateLimitConfig{RequestsPerSecond: 100, Burst: 20}
	if v := os.Getenv("RATE_LIMIT_RPS"); v != "" {
		if parsed, err := strconv.ParseFloat(v, 64); err == nil {
			cfg.RequestsPerSecond = parsed
		}
	}
	if v := os.Getenv("RATE_LIMIT_BURST"); v != "" {
		if parsed, err := strconv.Atoi(v); err == nil {
			cfg.Burst = parsed
		}
	}
	return cfg
}
```

```go
// cmd/server/main.go — 실제 코드(발췌)
rateLimitConfig := config.LoadRateLimitConfig()
limiter := rate.NewLimiter(rate.Limit(rateLimitConfig.RequestsPerSecond), rateLimitConfig.Burst)

accountRepo := persistence.NewAccountRepository(db, outboxWriter)
mux, healthHandler := httphandler.NewRouter(accountRepo, jwtService, limiter)
```

```go
// internal/interface/http/router.go — 실제 코드(발췌)
limited := http.NewServeMux()
limited.Handle("/accounts", middleware.RequireAuth(jwtService)(protected))
limited.Handle("/accounts/", middleware.RequireAuth(jwtService)(protected))
limited.HandleFunc("POST /auth/sign-in", authHTTP.SignIn)

mux := http.NewServeMux()
mux.Handle("/", middleware.RateLimit(limiter)(limited))
// 헬스체크는 오케스트레이터 프로브 전용이므로 rate limit 미들웨어를 감싸지 않는다.
mux.HandleFunc("GET /health/live", healthHandler.Live)
mux.HandleFunc("GET /health/ready", healthHandler.Ready)

return middleware.CorrelationID(mux), healthHandler
```

`limiter`를 `NewRouter`의 파라미터로 받는 이유는 운영값과 테스트값을 분리하기 위해서다 — `main()`은 `LoadRateLimitConfig()`(기본값: 초당 100개, burst 20개)로 만든 limiter를 넘기고, `test/account_e2e_test.go`의 e2e 테스트는 같은 프로세스 안에서 짧은 시간에 수십 개 요청을 보내므로 훨씬 넉넉한 limiter(`rate.NewLimiter(rate.Limit(100_000), 100_000)`)를 직접 만들어 넘긴다. kotlin-springboot(`resilience4j.ratelimiter.instances.http-write.limit-for-period`를 테스트 프로퍼티로 override)·fastapi(`RATE_LIMIT_*` 환경 변수를 테스트 conftest에서 override)와 같은 이유·같은 패턴이다.

---

## 클라이언트(IP)별 제한 — limiter map (아직 미구현, 확장 옵션)

현재 구현된 전역 limiter 하나만 두면 특정 클라이언트가 아니라 서버 전체가 같은 버킷을 나눠 쓰게 된다. 클라이언트별로 제한하려면 IP(또는 인증된 사용자 ID)마다 별도 `*rate.Limiter`를 두고 map으로 관리한다 — `golang.org/x/time/rate` 공식 문서가 권장하는 관용구다. 아래는 이 저장소가 아직 채택하지 않은 목표(forward-looking) 설계다.

```go
// internal/interface/http/middleware/rate_limit_middleware.go
package middleware

import (
	"net"
	"net/http"
	"sync"
	"time"

	"golang.org/x/time/rate"
)

type clientLimiter struct {
	limiter  *rate.Limiter
	lastSeen time.Time
}

// PerClientRateLimit은 클라이언트(원격 IP)별로 독립된 토큰 버킷을 적용한다.
type PerClientRateLimit struct {
	mu       sync.Mutex
	clients  map[string]*clientLimiter
	r        rate.Limit
	b        int
}

func NewPerClientRateLimit(r rate.Limit, b int) *PerClientRateLimit {
	rl := &PerClientRateLimit{clients: make(map[string]*clientLimiter), r: r, b: b}
	go rl.cleanupLoop() // 오래된 클라이언트 항목을 주기적으로 제거 — map이 무한정 커지지 않도록
	return rl
}

func (rl *PerClientRateLimit) getLimiter(key string) *rate.Limiter {
	rl.mu.Lock()
	defer rl.mu.Unlock()

	c, ok := rl.clients[key]
	if !ok {
		c = &clientLimiter{limiter: rate.NewLimiter(rl.r, rl.b)}
		rl.clients[key] = c
	}
	c.lastSeen = time.Now()
	return c.limiter
}

func (rl *PerClientRateLimit) cleanupLoop() {
	for range time.Tick(time.Minute) {
		rl.mu.Lock()
		for key, c := range rl.clients {
			if time.Since(c.lastSeen) > 3*time.Minute {
				delete(rl.clients, key)
			}
		}
		rl.mu.Unlock()
	}
}

func (rl *PerClientRateLimit) Middleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		host, _, err := net.SplitHostPort(r.RemoteAddr)
		if err != nil {
			host = r.RemoteAddr
		}
		if !rl.getLimiter(host).Allow() {
			// 실제 RateLimit 미들웨어와 동일하게 표준 JSON 에러 스키마로 응답한다(위 "전역 미들웨어" 절 참고).
			http.Error(w, "too many requests", http.StatusTooManyRequests)
			return
		}
		next.ServeHTTP(w, r)
	})
}
```

`sync.Mutex`로 map 접근을 보호하는 이유는 여러 요청 고루틴이 동시에 `getLimiter`를 호출하기 때문이다 — 이 저장소의 다른 동시성 코드와 동일하게 "공유 상태는 명시적으로 잠근다"는 원칙을 따른다. `cleanupLoop`이 없으면 유니크한 클라이언트가 계속 늘어날 때 map이 무한정 커지는 메모리 누수가 생긴다.

---

## 엔드포인트별 차등 적용 (아직 미구현, 확장 옵션)

현재는 `/accounts`·`/auth/sign-in` 등 모든 API 라우트가 같은 전역 limiter를 공유한다. NestJS의 `@Throttle()`/`@SkipThrottle()` 데코레이터에 대응하는 것은, Go에서는 **라우트마다 다른 미들웨어로 감싸는** 것이다([cross-cutting-concerns.md](cross-cutting-concerns.md)의 미들웨어 합성 패턴 참고) — 아래는 이 저장소가 아직 채택하지 않은 목표 설계다.

```go
// 쓰기 엔드포인트(입출금)는 더 엄격하게, 헬스체크는 제외
writeLimiter := rate.NewLimiter(rate.Limit(5), 2)
readLimiter := rate.NewLimiter(rate.Limit(50), 10)

mux.Handle("POST /accounts/{id}/deposit",
	middleware.RateLimit(writeLimiter)(http.HandlerFunc(accountHTTP.Deposit)))
mux.Handle("GET /accounts/{id}",
	middleware.RateLimit(readLimiter)(http.HandlerFunc(accountHTTP.GetAccount)))
mux.HandleFunc("GET /health/live", healthHandler.Live) // rate limit 미적용 — 오케스트레이터 프로브 제외
```

NestJS처럼 "전역 Guard + 데코레이터로 예외 표시" 구조가 아니라, **각 라우트를 등록할 때 원하는 미들웨어만 골라 감싸는** 방식이다 — 데코레이터가 없는 대신 조립이 명시적이라 어떤 라우트가 어떤 제한을 받는지 `router.go` 한 곳만 보면 알 수 있다.

---

## 응답 헤더 — 수동으로 채워야 한다

`@nestjs/throttler`는 `X-RateLimit-*` 헤더를 자동으로 채워주지만, `x/time/rate`는 그런 편의 기능이 없다 — 필요하면 미들웨어에서 직접 채운다.

```go
if !limiter.Allow() {
	w.Header().Set("Retry-After", "1")
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusTooManyRequests)
	// ... rateLimitErrorResponse를 인코딩해 표준 JSON 에러 스키마로 응답
	return
}
```

`limiter.Tokens()`로 남은 토큰 수를 근사치로 노출할 수는 있지만, 정확한 `X-RateLimit-Remaining`/`X-RateLimit-Reset`을 제공하려면 `rate.Limiter` 위에 별도 계측 레이어를 얹어야 한다 — 이 저장소는 아직 여기까지 다루지 않는다. `Retry-After` 헤더는 채운다. 표준 JSON 에러 응답 스키마(`statusCode`/`code`/`message`/`error`)로 429를 감싸는 것은 위 "전역 미들웨어" 절에서 이미 다뤘다 — [error-handling.md](error-handling.md)와 동일한 스키마를 쓴다.

---

## 운영값 조정

`RATE_LIMIT_RPS`(초당 평균 허용 요청 수, 기본 100)·`RATE_LIMIT_BURST`(순간 burst 크기, 기본 20) 환경 변수를 배포 환경에 설정하고 프로세스를 재기동하면, 코드 변경·재빌드 없이 임계값을 조정할 수 있다 — `cmd/server/main.go`가 기동 시 `config.LoadRateLimitConfig()`로 이 값을 읽어 `rate.Limiter`를 조립하기 때문이다(위 "전역 미들웨어" 절 참고). nestjs(`THROTTLE_*`)·fastapi(`RATE_LIMIT_DEFAULT`/`RATE_LIMIT_WRITE`)도 동일한 방향의 환경 변수 기반 조정을 지원하며, java/kotlin-springboot는 `application.yml` + Spring profiles 기반으로 사실상 동급의 배포 시점 유연성을 갖는다(각 언어 문서의 "운영값 조정" 절 참고).

## 원칙

- **미들웨어 체인의 앞쪽에 배치**한다 — 인증보다 먼저 걸러야 낭비되는 연산이 적다([cross-cutting-concerns.md](cross-cutting-concerns.md) 파이프라인 순서 참고). ✅ `router.go`에서 `RequireAuth`보다 바깥쪽에서 `RateLimit`이 적용된다.
- **헬스체크/내부 엔드포인트는 라우트 등록 시 아예 미들웨어를 감싸지 않는 방식으로 제외**한다. ✅ `/health/live`·`/health/ready`는 `mux`에 직접 등록되고 `RateLimit`으로 감싼 서브 mux 밖에 있다.
- **환경 변수로 임계값을 관리**한다([config.md](config.md) 패턴과 동일하게 `RateLimitConfig` 구조체로 분리). ✅ `internal/config/rate_limit.go`의 `LoadRateLimitConfig()`가 `RATE_LIMIT_RPS`/`RATE_LIMIT_BURST`를 읽는다. 운영 기본값과 e2e 테스트 값(넉넉한 limiter)을 분리하는 데도 같은 주입 지점을 쓴다.
- **전역 제한과 클라이언트별 제한을 구분**한다 — 서버 보호가 목적이면 전역 limiter, 클라이언트 남용 방지가 목적이면 클라이언트별 map을 쓴다. 현재는 전역 limiter만 적용되어 있다(위 "클라이언트별 제한" 섹션은 미구현 확장 옵션).
- **클라이언트별 map을 도입하면 반드시 정리(cleanup)한다** — 그렇지 않으면 메모리 누수가 된다.
- **쓰기 엔드포인트를 읽기보다 엄격하게 제한**한다 — 위 "엔드포인트별 차등 적용" 섹션은 미구현 확장 옵션이다.

---

## 실제 배선 여부는 harness가 자동 검사한다

rate limit 미들웨어가 정의만 되어 있고 router.go/main.go 같은 조립 지점 어디에서도
호출되지 않는(죽은 코드가 되는) 회귀는 `implementations/go/harness/rate_limit_wired.go`
(`rate-limit-wired` 규칙)가 자동으로 검사한다 — `internal/interface/http/middleware/`
아래 이름에 `RateLimit`이 들어간 함수/메서드를 찾아, 정의 파일과 테스트 파일을 제외한
나머지 소스 어딘가에서 실제로 호출되는지 확인한다.

### 관련 문서

- [cross-cutting-concerns.md](cross-cutting-concerns.md) — 미들웨어 체인 합성 패턴, 파이프라인 순서
- [error-handling.md](error-handling.md) — 429 응답이 따르는 표준 JSON 에러 스키마
- [config.md](config.md) — 임계값을 환경 변수 기반 설정 구조체로 분리하는 패턴
- [observability.md](observability.md) — rate limit 거부 발생 시 로깅

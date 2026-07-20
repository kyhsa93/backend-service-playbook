# 인증 패턴 (Go)

원칙은 루트 [authentication.md](../../../../docs/architecture/authentication.md)를 따른다: 인증은 **Interface 레이어에서만** 처리하고, Application/Domain 레이어는 인증 컨텍스트에 의존하지 않는다. Go에는 NestJS의 `@UseGuards(AuthGuard)` 같은 데코레이터 기반 메커니즘이 없으므로, `net/http`의 미들웨어(핸들러를 감싸는 함수) 패턴으로 동일한 역할을 구현한다.

---

## JWT/Bearer 인증 + 비밀번호 검증

아래 패턴은 `examples/`에 그대로 구현되어 있다 — `internal/infrastructure/auth/jwt_service.go`(JWT 발급/검증), `internal/interface/http/middleware/auth_middleware.go`(Bearer 토큰 검증 미들웨어), `internal/interface/http/router.go`(라우트 그룹 단위 미들웨어 적용)가 이 문서가 서술하는 상태 그대로다. `account_handler.go`는 `middleware.UserIDFromContext(r.Context())`로 인증된 사용자 ID를 꺼낸다.

`POST /auth/sign-in`은 `internal/domain/credential/` Credential Aggregate + `PasswordHasher` Technical Service(bcrypt)로 저장된 해시와 비교한 뒤에만 JWT를 발급한다.

---

## 인증 흐름

```
[가입]
클라이언트 → POST /auth/sign-up { userId, password }
          → SignUpHandler: 아이디 중복 확인 → PasswordHasher.Hash() → CredentialRepository.Save()
          → 클라이언트: 201

[토큰 발급]
클라이언트 → POST /auth/sign-in { userId, password }
           → SignInHandler: CredentialRepository.FindByUserID()로 저장된 해시 조회
                           → PasswordHasher.Verify()로 비밀번호 검증
                           → 검증 성공 시 TokenIssuer.Sign(userID) → JWT 발급
           → 클라이언트: { "accessToken": "..." }

[인증 요청]
클라이언트 → Authorization: Bearer <access_token> 헤더 포함
          → AuthMiddleware: 헤더에서 토큰 추출 → JWTService.Verify(token)
          → context.Context에 사용자 정보 주입 → 다음 핸들러로 전달
```

**아이디 미존재와 비밀번호 불일치는 동일한 에러(`credential.ErrInvalidCredentials` → HTTP 401 `INVALID_CREDENTIALS`)로 응답한다** — 둘을 구분해서 응답하면 공격자가 존재하는 아이디를 추측할 수 있다(user enumeration). `SignInHandler.Handle`(`internal/application/command/sign_in_handler.go`)이 "아이디 없음"과 "비밀번호 불일치" 두 분기 모두에서 같은 sentinel error를 반환하는 방식으로 이를 강제한다.

---

## 디렉토리 구조

```
internal/
  domain/
    account/                       ← 기존 도메인, 인증 개념 없음
    credential/                    ← Credential Aggregate — Auth 전용 도메인
      credential.go                ← credentialId, userId, passwordHash
      repository.go                ← Repository interface (FindByUserID, Save)
      errors.go                    ← ErrNotFound, ErrInvalidCredentials, ErrUserIDAlreadyExists
  application/
    command/
      password_hasher.go           ← PasswordHasher 포트 (Technical Service, 인터페이스만)
      token_issuer.go               ← TokenIssuer 포트 (Sign(userID) (string, error))
      sign_up_handler.go            ← 아이디 중복 확인 → 해싱 → 저장
      sign_in_handler.go            ← 해시 조회 → 검증 → 토큰 발급
  infrastructure/
    auth/
      jwt_service.go                ← JWT 발급/검증 구현체 (TokenIssuer를 구조적으로 만족)
      bcrypt_password_hasher.go     ← PasswordHasher 구현체 (golang.org/x/crypto/bcrypt)
    persistence/
      credential_repository.go      ← credential.Repository 구현체
  interface/
    http/
      middleware/
        auth_middleware.go          ← Bearer 토큰 추출 + context 주입
      auth_handler.go                ← POST /auth/sign-up, POST /auth/sign-in
      dto.go                         ← SignUpRequest / SignInRequest / SignInResponse
migrations/
  0005_add_credential.sql           ← credentials 테이블(user_id UNIQUE)
```

**패키지 이름 주의**: domain 패키지는 `auth`가 아니라 `credential`이다. `internal/infrastructure/auth`가 이미 JWT 관련 구현체들의 자리로 쓰이고 있어, domain 패키지까지 `auth`로 이름 붙이면 이 둘을 한 파일에서 동시에 import해야 하는 곳(`router.go` 등)에서 Go의 패키지명 충돌이 발생한다 — Go는 import 별칭으로 우회할 수 있지만, 애초에 이름을 겹치지 않게 짓는 편이 `account`/`card`가 이미 따르는 관용(패키지명 = 주요 Aggregate 이름)과도 더 잘 맞는다.

---

## AuthService — 토큰 발급 및 검증

Go 표준 라이브러리에는 JWT 구현이 없으므로 `github.com/golang-jwt/jwt/v5` 같은 최소 의존성을 사용한다. 인터페이스는 Application 레이어의 다른 Technical Service(예: 아래의 `PasswordHasher`, [cross-domain.md](cross-domain.md)의 `AccountAdapter`)와 동일하게, **인터페이스는 사용하는 레이어 근처에 두고 구현체는 infrastructure에 둔다**.

```go
// internal/infrastructure/auth/jwt_service.go
package auth

import (
	"errors"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

var ErrInvalidToken = errors.New("invalid or expired token")

type Claims struct {
	UserID string `json:"userId"`
	jwt.RegisteredClaims
}

type JWTService struct {
	secret []byte
	ttl    time.Duration
}

func NewJWTService(secret string, ttl time.Duration) *JWTService {
	return &JWTService{secret: []byte(secret), ttl: ttl}
}

// Sign은 최소한의 정보(userId)만 담은 JWT를 발급한다.
func (s *JWTService) Sign(userID string) (string, error) {
	claims := Claims{
		UserID: userID,
		RegisteredClaims: jwt.RegisteredClaims{
			ExpiresAt: jwt.NewNumericDate(time.Now().Add(s.ttl)),
			IssuedAt:  jwt.NewNumericDate(time.Now()),
		},
	}
	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	return token.SignedString(s.secret)
}

// Verify는 토큰을 검증하고 userId를 반환한다. 서명/만료 검증 실패 시 ErrInvalidToken.
func (s *JWTService) Verify(tokenString string) (string, error) {
	claims := &Claims{}
	token, err := jwt.ParseWithClaims(tokenString, claims, func(t *jwt.Token) (any, error) {
		return s.secret, nil
	})
	if err != nil || !token.Valid {
		return "", ErrInvalidToken
	}
	return claims.UserID, nil
}
```

**payload에는 `userId`만 담는다** — root 원칙과 동일하게, 역할/권한처럼 자주 바뀌는 정보나 이메일 같은 민감 정보는 넣지 않는다. JWT payload는 서명만 될 뿐 암호화되지 않으므로 base64 디코딩으로 누구나 읽을 수 있다.

`*JWTService`는 위 `Sign(userID string) (string, error)` 시그니처만으로 이미 아래 `TokenIssuer` 포트를 구조적으로 만족한다 — Go의 구조적 타이핑 덕분에 별도 어댑터 타입이 필요 없다.

---

## Credential — 비밀번호 검증

`internal/domain/credential/`에 있는 Credential Aggregate가 `userId`/`passwordHash`를 보관한다. 평문 비밀번호는 domain/application 어디에도 저장하지 않는다.

```go
// internal/domain/credential/credential.go
package credential

type Credential struct {
	CredentialID string
	UserID       string
	PasswordHash string
	CreatedAt    time.Time
}

func New(userID, passwordHash string) *Credential { /* ... */ }

// internal/domain/credential/repository.go
type Repository interface {
	FindByUserID(ctx context.Context, userID string) (*Credential, error)
	Save(ctx context.Context, credential *Credential) error
}
```

`PasswordHasher`는 [domain-service.md](domain-service.md)의 Technical Service 패턴이다 — `AccountAdapter`와 동일하게, 인터페이스는 그것을 사용하는 `application/command/`에 정의하고 구현체(bcrypt)는 `infrastructure/`에 둔다.

```go
// internal/application/command/password_hasher.go — 포트(인터페이스만)
package command

type PasswordHasher interface {
	Hash(plainPassword string) (string, error)
	Verify(plainPassword, passwordHash string) (bool, error)
}

// internal/infrastructure/auth/bcrypt_password_hasher.go — 구현체
package auth

import "golang.org/x/crypto/bcrypt"

const bcryptCost = 12

type BcryptPasswordHasher struct{}

func (h *BcryptPasswordHasher) Hash(plainPassword string) (string, error) {
	hash, err := bcrypt.GenerateFromPassword([]byte(plainPassword), bcryptCost)
	return string(hash), err
}

func (h *BcryptPasswordHasher) Verify(plainPassword, passwordHash string) (bool, error) {
	err := bcrypt.CompareHashAndPassword([]byte(passwordHash), []byte(plainPassword))
	if err == nil {
		return true, nil
	}
	if errors.Is(err, bcrypt.ErrMismatchedHashAndPassword) {
		return false, nil
	}
	return false, err
}
```

---

## SignInHandler — 조회 → 검증 → 토큰 발급

```go
// internal/application/command/sign_in_handler.go
package command

type SignInHandler struct {
	repo           credential.Repository
	passwordHasher PasswordHasher
	tokenIssuer    TokenIssuer
}

func (h *SignInHandler) Handle(ctx context.Context, cmd SignInCommand) (string, error) {
	c, err := h.repo.FindByUserID(ctx, cmd.UserID)
	if err != nil {
		if errors.Is(err, credential.ErrNotFound) {
			return "", credential.ErrInvalidCredentials // 아이디 미존재 → 비밀번호 불일치와 동일한 에러
		}
		return "", err
	}

	valid, err := h.passwordHasher.Verify(cmd.Password, c.PasswordHash)
	if err != nil {
		return "", err
	}
	if !valid {
		return "", credential.ErrInvalidCredentials // 비밀번호 불일치
	}

	return h.tokenIssuer.Sign(c.UserID)
}
```

Interface 레이어(`auth_handler.go`)는 `credential.ErrInvalidCredentials`를 `errors.Is`로 매핑해 HTTP 401 `INVALID_CREDENTIALS`로 변환한다(error-handling.md의 `accountErrorMapping`과 동일한 관용구 — `authErrorMapping`). `credential.ErrUserIDAlreadyExists`(가입 시 아이디 중복)는 400 `USER_ID_ALREADY_EXISTS`로 매핑한다 — 이 저장소가 "현재 상태와 충돌하는 요청"류를 전부 400으로 매핑하는 기존 관용(`account.ErrAlreadyClosed` 등)을 그대로 따른 것이고, nestjs 구현(`BadRequestException`)과도 일치한다.

---

## AuthMiddleware — `net/http` 미들웨어로 Bearer 토큰 검증

Go의 미들웨어는 `func(http.Handler) http.Handler` 시그니처를 가진 함수다. 인증된 사용자 정보는 `context.Context`에 담아 다음 핸들러로 전달한다 — NestJS의 `request.user` 할당과 동일한 역할을 `context.WithValue`로 수행한다.

```go
// internal/interface/http/middleware/auth_middleware.go
package middleware

import (
	"context"
	"net/http"
	"strings"

	"github.com/example/account-service/internal/infrastructure/auth"
)

type contextKey string

const userIDKey contextKey = "userID"

// RequireAuth는 Authorization: Bearer 헤더를 검증하고, 통과하면 context에
// userID를 주입한 뒤 next를 호출한다. 검증 실패 시 401을 반환하고 next를 호출하지 않는다.
func RequireAuth(jwtService *auth.JWTService) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			authorization := r.Header.Get("Authorization")
			if !strings.HasPrefix(authorization, "Bearer ") {
				http.Error(w, "unauthorized", http.StatusUnauthorized)
				return
			}
			token := strings.TrimPrefix(authorization, "Bearer ")
			userID, err := jwtService.Verify(token)
			if err != nil {
				http.Error(w, "unauthorized", http.StatusUnauthorized)
				return
			}
			ctx := context.WithValue(r.Context(), userIDKey, userID)
			next.ServeHTTP(w, r.WithContext(ctx))
		})
	}
}

// UserIDFromContext는 RequireAuth를 통과한 요청의 context에서 userID를 꺼낸다.
func UserIDFromContext(ctx context.Context) (string, bool) {
	userID, ok := ctx.Value(userIDKey).(string)
	return userID, ok
}
```

---

## 라우터 등록 — 그룹 단위로 미들웨어 적용

root는 "Guard는 Controller 클래스 레벨에 적용, 메서드 레벨은 누락 위험"이라고 규정한다. Go에는 클래스가 없으므로, **라우트 그룹 전체를 미들웨어로 감싸는 것**으로 동일한 효과를 낸다. 실제 `internal/interface/http/router.go`(발췌, 계좌/카드 라우트는 생략):

```go
func NewRouter(
	repo account.Repository, cardRepo card.Repository, credentialRepo credential.Repository,
	accountAdapter command.AccountAdapter,
	jwtService *auth.JWTService, passwordHasher command.PasswordHasher, limiter *rate.Limiter,
) (http.Handler, *HealthHandler) {
	// ... accountHTTP, cardHTTP 조립 생략

	// jwtService는 이미 command.TokenIssuer를 구조적으로 만족하므로 그대로 주입한다.
	signUpHandler := command.NewSignUpHandler(credentialRepo, passwordHasher)
	signInHandler := command.NewSignInHandler(credentialRepo, passwordHasher, jwtService)
	authHTTP := NewAuthHandler(signUpHandler, signInHandler)

	protected := http.NewServeMux()
	protected.HandleFunc("POST /accounts", accountHTTP.CreateAccount)
	// ... 나머지 계좌/카드 엔드포인트

	limited := http.NewServeMux()
	limited.Handle("/accounts", middleware.RequireAuth(jwtService)(protected)) // 인증 필요 — 그룹 전체
	limited.Handle("/accounts/", middleware.RequireAuth(jwtService)(protected))
	limited.HandleFunc("POST /auth/sign-up", authHTTP.SignUp) // 인증 불필요
	limited.HandleFunc("POST /auth/sign-in", authHTTP.SignIn) // 인증 불필요

	mux := http.NewServeMux()
	mux.Handle("/", middleware.RateLimit(limiter)(limited))
	mux.HandleFunc("GET /health/live", healthHandler.Live)
	mux.HandleFunc("GET /health/ready", healthHandler.Ready)
	return middleware.CorrelationID(mux), healthHandler // Correlation ID는 인증보다 먼저 적용 — observability.md 참고
}
```

(`GET /health/live`·`GET /health/ready` 같은 헬스체크 엔드포인트는 인증도, rate limit도 걸리지 않은 채 `mux`에 직접 등록된다 — [graceful-shutdown.md](graceful-shutdown.md), [rate-limiting.md](rate-limiting.md) 참고.)

- 인증이 필요한 라우트를 **하나의 `http.ServeMux`로 묶고 미들웨어로 감싸는 것**이 "메서드별로 미들웨어를 따로 붙이는 실수"를 방지하는 Go식 방법이다. 새 엔드포인트를 이 서브 mux에 추가하기만 하면 자동으로 인증이 적용된다.
- 인증 불필요 엔드포인트(`/auth/sign-up`, `/auth/sign-in`, `/health/*`)는 `RequireAuth`로 감싸지 않은 mux에 등록한다.

---

## Handler에서 사용 — context에서 userID만 꺼낸다

Application/Domain 레이어는 토큰이나 인증 컨텍스트를 전혀 모른다. Interface 레이어가 `context`에서 꺼낸 `userID`를 Command/Query의 필드로 담아 넘긴다 — 지금 코드의 `RequesterID` 필드가 정확히 이 역할이다.

```go
func (h *AccountHandler) CreateAccount(w http.ResponseWriter, r *http.Request) {
	userID, _ := middleware.UserIDFromContext(r.Context()) // 인증된 사용자 ID (검증 완료)
	var body CreateAccountRequest
	json.NewDecoder(r.Body).Decode(&body)

	a, err := h.createAccount.Handle(r.Context(), command.CreateAccountCommand{
		RequesterID: userID, // Application 레이어는 이 값이 어떻게 검증됐는지 모른다
		Email:       body.Email,
		Currency:    body.Currency,
	})
	// ...
}
```

이렇게 하면 `account.New()`, `CreateAccountHandler.Handle()` 등 Application/Domain 코드는 오늘의 `X-User-Id` 자리표시자를 JWT 기반으로 교체해도 **전혀 변경할 필요가 없다** — 인증 방식 변경이 Interface 레이어에 국한된다는 것이 레이어 분리의 실익이다.

---

## 원칙 요약

- **인증은 Interface 레이어 전용**: `internal/interface/http/middleware/`에만 둔다. Domain/Application 패키지는 `jwt` 패키지를 import하지 않는다.
- **JWT payload는 최소한으로**: `userId`만 담는다.
- **미들웨어는 라우트 그룹 단위 적용**: 개별 핸들러마다 감싸지 않는다 — 새 엔드포인트 추가 시 누락 위험을 없앤다.
- **`context.Context`로 인증 정보 전파**: `context.WithValue` + 전용 키 타입(`contextKey`)으로 타입 충돌을 방지한다.
- **sign-in은 반드시 저장된 해시와 비교한 뒤에만 토큰을 발급한다**: userId만으로 토큰을 발급하지 않는다. 아이디 미존재/비밀번호 불일치는 같은 에러로 응답해 user enumeration을 막는다.
- **비밀번호 해싱은 Technical Service로 분리한다**: `PasswordHasher` 인터페이스는 `application/command/`, bcrypt 구현체는 `infrastructure/auth/`에 둔다 — Domain/Application이 `bcrypt` 라이브러리에 직접 의존하지 않는다.

---

### 관련 문서

- [cross-cutting-concerns.md](cross-cutting-concerns.md) — 미들웨어 체인에서 인증의 위치
- [layer-architecture.md](layer-architecture.md) — Interface 레이어 역할
- [error-handling.md](error-handling.md) — 401/403 응답 매핑

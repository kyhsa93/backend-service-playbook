# Authentication Pattern (Go)

The principle follows the root [authentication.md](../../../../docs/architecture/authentication.md): authentication is handled **only in the Interface layer**, and the Application/Domain layers never depend on the authentication context. Since Go has no decorator-based mechanism like NestJS's `@UseGuards(AuthGuard)`, the same role is implemented with `net/http`'s middleware pattern (functions that wrap a handler).

---

## JWT/Bearer authentication + password verification

The pattern below is implemented as-is in `examples/` — `internal/infrastructure/auth/jwt_service.go` (JWT issuance/verification), `internal/interface/http/middleware/auth_middleware.go` (the Bearer token verification middleware), and `internal/interface/http/router.go` (applying middleware per route group) are exactly in the state this document describes. `account_handler.go` pulls out the authenticated user ID via `middleware.UserIDFromContext(r.Context())`.

`POST /auth/sign-in` only issues a JWT after comparing against the stored hash, using the `internal/domain/credential/` Credential Aggregate + the `PasswordHasher` Technical Service (bcrypt).

---

## Authentication flow

```
[Sign-up]
Client → POST /auth/sign-up { userId, password }
          → SignUpHandler: check for duplicate ID → PasswordHasher.Hash() → CredentialRepository.Save()
          → Client: 201

[Token issuance]
Client → POST /auth/sign-in { userId, password }
           → SignInHandler: look up the stored hash via CredentialRepository.FindByUserID()
                           → verify the password via PasswordHasher.Verify()
                           → on successful verification, TokenIssuer.Sign(userID) → JWT issued
           → Client: { "accessToken": "..." }

[Authenticated request]
Client → includes an Authorization: Bearer <access_token> header
          → AuthMiddleware: extract the token from the header → JWTService.Verify(token)
          → inject user info into context.Context → passed to the next handler
```

**A nonexistent ID and a mismatched password respond with the same error** (`credential.ErrInvalidCredentials` → HTTP 401 `INVALID_CREDENTIALS`) — responding differently for each would let an attacker guess which IDs exist (user enumeration). `SignInHandler.Handle` (`internal/application/command/sign_in_handler.go`) enforces this by returning the same sentinel error in both the "ID not found" and "password mismatch" branches.

---

## Directory structure

```
internal/
  domain/
    account/                       ← existing domain, no authentication concept
    credential/                    ← Credential Aggregate — an Auth-only domain
      credential.go                ← credentialId, userId, passwordHash
      repository.go                ← Repository interface (FindByUserID, Save)
      errors.go                    ← ErrNotFound, ErrInvalidCredentials, ErrUserIDAlreadyExists
  application/
    command/
      password_hasher.go           ← the PasswordHasher port (Technical Service, interface only)
      token_issuer.go               ← the TokenIssuer port (Sign(userID) (string, error))
      sign_up_handler.go            ← check for duplicate ID → hash → save
      sign_in_handler.go            ← look up hash → verify → issue token
  infrastructure/
    auth/
      jwt_service.go                ← JWT issue/verify implementation (structurally satisfies TokenIssuer)
      bcrypt_password_hasher.go     ← PasswordHasher implementation (golang.org/x/crypto/bcrypt)
    persistence/
      credential_repository.go      ← credential.Repository implementation
  interface/
    http/
      middleware/
        auth_middleware.go          ← Bearer token extraction + context injection
      auth_handler.go                ← POST /auth/sign-up, POST /auth/sign-in
      dto.go                         ← SignUpRequest / SignInRequest / SignInResponse
migrations/
  0005_add_credential.sql           ← the credentials table (user_id UNIQUE)
```

**A note on package naming**: the domain package is `credential`, not `auth`. Since `internal/infrastructure/auth` is already used as the home for the JWT-related implementations, naming the domain package `auth` too would cause a Go package-name collision anywhere both need to be imported in the same file (`router.go`, etc.) — Go could work around this with an import alias, but not naming them the same to begin with fits better with the convention `account`/`card` already follow (package name = the main Aggregate name).

---

## AuthService — token issuance and verification

Since the Go standard library has no JWT implementation, a minimal dependency such as `github.com/golang-jwt/jwt/v5` is used. As with any other Technical Service in the Application layer (e.g. `PasswordHasher` below, or `AccountAdapter` in [cross-domain.md](cross-domain.md)), **the interface lives near the layer that uses it, and the implementation lives in infrastructure**.

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

// Sign issues a JWT carrying only the minimum information (userId).
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

// Verify validates the token and returns the userId. Returns ErrInvalidToken
// if signature/expiration validation fails.
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

**The payload carries only `userId`** — as with the root principle, frequently-changing information like roles/permissions, or sensitive information like email, is never included. Since a JWT payload is only signed, not encrypted, anyone can read it via base64 decoding.

`*JWTService` already structurally satisfies the `TokenIssuer` port below purely by having the `Sign(userID string) (string, error)` signature — thanks to Go's structural typing, no separate adapter type is needed.

---

## Credential — password verification

The Credential Aggregate in `internal/domain/credential/` holds `userId`/`passwordHash`. The plaintext password is never stored anywhere in domain/application.

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

`PasswordHasher` is the Technical Service pattern from [domain-service.md](domain-service.md) — just like `AccountAdapter`, the interface is defined in `application/command/`, the layer that uses it, and the implementation (bcrypt) lives in `infrastructure/`.

```go
// internal/application/command/password_hasher.go — the port (interface only)
package command

type PasswordHasher interface {
	Hash(plainPassword string) (string, error)
	Verify(plainPassword, passwordHash string) (bool, error)
}

// internal/infrastructure/auth/bcrypt_password_hasher.go — the implementation
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

## SignInHandler — lookup → verify → issue token

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
			return "", credential.ErrInvalidCredentials // ID not found → same error as a password mismatch
		}
		return "", err
	}

	valid, err := h.passwordHasher.Verify(cmd.Password, c.PasswordHash)
	if err != nil {
		return "", err
	}
	if !valid {
		return "", credential.ErrInvalidCredentials // password mismatch
	}

	return h.tokenIssuer.Sign(c.UserID)
}
```

The Interface layer (`auth_handler.go`) maps `credential.ErrInvalidCredentials` via `errors.Is` into HTTP 401 `INVALID_CREDENTIALS` (the same idiom as `accountErrorMapping` in error-handling.md — `authErrorMapping`). `credential.ErrUserIDAlreadyExists` (a duplicate ID at sign-up) is mapped to 400 `USER_ID_ALREADY_EXISTS` — this follows the same existing convention this repository uses for mapping every "request conflicts with the current state" case to 400 (`account.ErrAlreadyClosed`, etc.), and it also matches the nestjs implementation (`BadRequestException`).

---

## AuthMiddleware — verifying the Bearer token via `net/http` middleware

Go middleware is a function with the signature `func(http.Handler) http.Handler`. Authenticated user info is carried in `context.Context` to the next handler — the same role NestJS's `request.user` assignment plays is performed here via `context.WithValue`.

```go
// internal/interface/http/middleware/auth_middleware.go
package middleware

import (
	"context"
	"net/http"
	"strings"
)

type contextKey string

const userIDKey contextKey = "userID"

// TokenVerifier is the minimal port this middleware needs.
// Since internal/infrastructure/auth.JWTService.Verify(token string) (string, error)
// already structurally satisfies this signature, the interface is declared near
// where it's used (here) rather than importing the concrete type — this keeps
// the interface/ layer from directly importing infrastructure/ (see
// layer-architecture.md, the harness's interface-no-infrastructure rule).
type TokenVerifier interface {
	Verify(token string) (string, error)
}

// RequireAuth validates the Authorization: Bearer header, and if it passes,
// injects userID into context before calling next. On validation failure,
// it returns 401 and never calls next.
func RequireAuth(verifier TokenVerifier) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			authorization := r.Header.Get("Authorization")
			if !strings.HasPrefix(authorization, "Bearer ") {
				http.Error(w, "unauthorized", http.StatusUnauthorized)
				return
			}
			token := strings.TrimPrefix(authorization, "Bearer ")
			userID, err := verifier.Verify(token)
			if err != nil {
				http.Error(w, "unauthorized", http.StatusUnauthorized)
				return
			}
			ctx := context.WithValue(r.Context(), userIDKey, userID)
			next.ServeHTTP(w, r.WithContext(ctx))
		})
	}
}

// UserIDFromContext pulls the userID out of the context of a request that passed RequireAuth.
func UserIDFromContext(ctx context.Context) (string, bool) {
	userID, ok := ctx.Value(userIDKey).(string)
	return userID, ok
}
```

---

## Router registration — applying middleware at the group level

The root document specifies that "a Guard is applied at the Controller class level; method-level application risks omission." Since Go has no classes, the same effect is achieved by **wrapping an entire route group with middleware**. The actual `internal/interface/http/router.go` (excerpt, account/card routes omitted):

```go
// tokenService bundles token issuance at sign-in (command.TokenIssuer) and token
// verification for the auth middleware (middleware.TokenVerifier) into one.
// Since *auth.JWTService already structurally satisfies both signatures, the
// interface/ layer (this file) never imports that concrete type directly — it
// only receives this interface. The actual assembly (creating *auth.JWTService)
// is handled by cmd/server/main.go, which can freely import infrastructure/.
type tokenService interface {
	command.TokenIssuer
	middleware.TokenVerifier
}

func NewRouter(
	repo account.Repository, cardRepo card.Repository, credentialRepo credential.Repository,
	accountAdapter command.AccountAdapter,
	jwtService tokenService, passwordHasher command.PasswordHasher, limiter *rate.Limiter,
) (http.Handler, *HealthHandler) {
	// ... assembly of accountHTTP, cardHTTP omitted

	// jwtService already structurally satisfies command.TokenIssuer, so it's injected as-is.
	signUpHandler := command.NewSignUpHandler(credentialRepo, passwordHasher)
	signInHandler := command.NewSignInHandler(credentialRepo, passwordHasher, jwtService)
	authHTTP := NewAuthHandler(signUpHandler, signInHandler)

	protected := http.NewServeMux()
	protected.HandleFunc("POST /accounts", accountHTTP.CreateAccount)
	// ... the remaining account/card endpoints

	limited := http.NewServeMux()
	limited.Handle("/accounts", middleware.RequireAuth(jwtService)(protected)) // authentication required — for the whole group
	limited.Handle("/accounts/", middleware.RequireAuth(jwtService)(protected))
	limited.HandleFunc("POST /auth/sign-up", authHTTP.SignUp) // no authentication needed
	limited.HandleFunc("POST /auth/sign-in", authHTTP.SignIn) // no authentication needed

	mux := http.NewServeMux()
	mux.Handle("/", middleware.RateLimit(limiter)(limited))
	mux.HandleFunc("GET /health/live", healthHandler.Live)
	mux.HandleFunc("GET /health/ready", healthHandler.Ready)
	return middleware.CorrelationID(mux), healthHandler // correlation ID is applied before authentication — see observability.md
}
```

(Healthcheck endpoints like `GET /health/live`/`GET /health/ready` are registered directly on `mux` with neither authentication nor rate limiting applied — see [graceful-shutdown.md](graceful-shutdown.md), [rate-limiting.md](rate-limiting.md).)

- Bundling every route that requires authentication into **a single `http.ServeMux` and wrapping it with middleware** is the Go way of preventing the mistake of "attaching middleware separately per method." Simply adding a new endpoint to this sub-mux automatically applies authentication.
- Endpoints that need no authentication (`/auth/sign-up`, `/auth/sign-in`, `/health/*`) are registered on a mux that isn't wrapped with `RequireAuth`.

Whether `internal/interface/http/` imports `internal/infrastructure/` directly is automatically checked by `implementations/go/harness/interface_no_infrastructure.go` (the `interface-no-infrastructure` rule) — it flags FAIL if this is reverted to importing `*auth.JWTService` directly instead of going through the `TokenVerifier`/`tokenService` interfaces.

---

## Usage in a Handler — pulling only the userID out of the context

The Application/Domain layers know nothing about tokens or the authentication context at all. The Interface layer pulls `userID` out of `context` and passes it in as a field on the Command/Query — the current code's `RequesterID` field plays exactly this role.

```go
func (h *AccountHandler) CreateAccount(w http.ResponseWriter, r *http.Request) {
	userID, _ := middleware.UserIDFromContext(r.Context()) // the authenticated user ID (already verified)
	var body CreateAccountRequest
	json.NewDecoder(r.Body).Decode(&body)

	a, err := h.createAccount.Handle(r.Context(), command.CreateAccountCommand{
		RequesterID: userID, // the Application layer doesn't know how this value was verified
		Email:       body.Email,
		Currency:    body.Currency,
	})
	// ...
}
```

This way, Application/Domain code like `account.New()` and `CreateAccountHandler.Handle()` **needs no change at all** even if today's `X-User-Id` placeholder is replaced with a JWT-based one — the practical benefit of layer separation is that a change in the authentication method stays confined to the Interface layer.

---

## Principle summary

- **Authentication is Interface-layer-only**: it lives only in `internal/interface/http/middleware/`. The Domain/Application packages never import the `jwt` package.
- **Keep the JWT payload minimal**: it carries only `userId`.
- **Middleware is applied at the route-group level**: it isn't wrapped around each individual handler — this eliminates the risk of omission when adding a new endpoint.
- **Authentication info propagates via `context.Context`**: `context.WithValue` plus a dedicated key type (`contextKey`) prevents type collisions.
- **Sign-in only ever issues a token after comparing against the stored hash**: a token is never issued from the userId alone. A nonexistent ID and a password mismatch respond with the same error, preventing user enumeration.
- **Password hashing is split out as a Technical Service**: the `PasswordHasher` interface lives in `application/command/`, and the bcrypt implementation lives in `infrastructure/auth/` — Domain/Application never directly depends on the `bcrypt` library.

---

### Related documents

- [cross-cutting-concerns.md](cross-cutting-concerns.md) — where authentication sits in the middleware chain
- [layer-architecture.md](layer-architecture.md) — the Interface layer's role
- [error-handling.md](error-handling.md) — mapping 401/403 responses

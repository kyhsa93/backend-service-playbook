# API Documentation / OpenAPI (Go)

Go-specific document — there's no corresponding document at the root beyond the framework-agnostic completeness bar in [api-response.md](../../../../docs/architecture/api-response.md)'s "Machine-readable API documentation (OpenAPI)" section. This document covers the Go-specific tooling choice and convention.

`net/http` has no framework-native OpenAPI/Swagger support (unlike NestJS's `@nestjs/swagger` decorators, which read TypeScript class/method metadata directly). This repository deliberately uses no web framework (module-pattern.md, directory-structure.md), so a framework-specific generator like `gin-swagger`/`echo-swagger` doesn't apply either. The chosen tool is [swaggo/swag](https://github.com/swaggo/swag) — a comment-annotation-based code generator, the closest Go equivalent to a decorator: annotations live directly above the handler function they document, in the same file, so the documentation cannot silently drift into a separate location the way a hand-maintained OpenAPI YAML file would.

---

## How it works

1. Every handler under `internal/interface/http/*.go` carries a block of `@`-prefixed comments directly above its `func` declaration (swag's "Declarative Comments Format").
2. A one-time general API info block sits above `func main()` in `cmd/server/main.go` (title/version/description/security scheme — the Go equivalent of nestjs's `main.ts` `DocumentBuilder`).
3. `swag init` (see "Regenerating" below) parses every annotated comment across the module and emits `internal/interface/http`-independent generated Go/JSON/YAML into `docs/`: `docs/docs.go` (a `swag.Register`-backed Go source file), `docs/swagger.json`, `docs/swagger.yaml`.
4. `cmd/server/main.go` blank-imports the generated `docs` package so its `init()` registers the spec; `internal/interface/http/router.go` mounts `github.com/swaggo/http-swagger/v2`'s `WrapHandler` at `GET /docs/`, which serves the Swagger UI (`/docs/index.html`) and the raw spec (`/docs/doc.json`) from that registered spec.

---

## A real annotated handler

```go
// internal/interface/http/account_handler.go — actual code

// Deposit credits an amount into an account.
//
// @Summary		Deposit money into an account
// @Description	Credits the given amount to the account and records a `DEPOSIT` transaction.
// @Tags			Account
// @Accept			json
// @Produce		json
// @Security		BearerAuth
// @Param			id		path		string					true	"The account ID"
// @Param			body	body		DepositRequest			true	"Deposit amount"
// @Success		201		{object}	TransactionResponse		"The deposit succeeded."
// @Failure		400		{object}	ErrorResponse			"One of: request validation failed (`VALIDATION_FAILED`), the amount is not a positive integer (`ACCOUNT_INVALID_AMOUNT`), or the account is not active (`ACCOUNT_DEPOSIT_REQUIRES_ACTIVE_ACCOUNT`)."
// @Failure		401		{object}	ErrorResponse			"The bearer token is missing, malformed, or invalid."
// @Failure		404		{object}	ErrorResponse			"No account exists with the given `id` for this requester (`ACCOUNT_NOT_FOUND`)."
// @Router			/accounts/{id}/deposit [post]
func (h *AccountHandler) Deposit(w http.ResponseWriter, r *http.Request) {
	// ...
}
```

Every `@Failure` line above was cross-checked against `accountErrorMapping` (`account_handler.go`'s own sentinel-error → HTTP-status table, error-handling.md) rather than guessed or copied from another domain — `Deposit` really can produce exactly these three 400 causes and this one 404 cause, no more and no fewer. Documenting a response the handler cannot actually produce (or omitting one it can) is the exact drift this convention exists to prevent (see api-response.md's "documentation that looks done" warning).

---

## DTO field descriptions

swag reads a struct field's doc comment (leading or same-line trailing) as that field's OpenAPI `description`:

```go
// internal/interface/http/dto.go — actual code
type DepositRequest struct {
	Amount int64 `json:"amount"` // The amount to credit. Must be a positive integer.
}
```

This repository uses the trailing same-line form throughout `dto.go`, since it keeps the diff against a pre-existing DTO field minimal (no reflowing the struct into a comment-above-each-field shape).

---

## The general API info block

```go
// cmd/server/main.go — actual code

// @title			Account Service API
// @version		0.1.0
// @description	API documentation for the DDD-based Account domain example service (Account/Card/Payment/Refund/Credential Bounded Contexts). Generated from the @-annotations above each handler in internal/interface/http/ by swag — never hand-edited.
// @BasePath		/
// @securityDefinitions.apikey	BearerAuth
// @in								header
// @name							Authorization
// @description					Type "Bearer" followed by a space and the JWT access token issued by `POST /auth/sign-in`.
func main() {
	// ...
}
```

`@securityDefinitions.apikey BearerAuth` + `@Security BearerAuth` on each protected handler is the swag equivalent of nestjs's `DocumentBuilder().addBearerAuth(..., 'token')` + `@ApiBearerAuth('token')`.

---

## `/health/*` is a narrow, deliberate exception

`internal/interface/http/health_handler.go`'s `Live` handler has no `@Failure` — it always returns 200, even mid-shutdown (graceful-shutdown.md), so there is no honest non-2xx response to document. `Ready`, by contrast, does have one (`@Failure 503`), since it genuinely can fail once shutdown starts. This mirrors [rate-limiting.md](rate-limiting.md)'s reasoning for excluding health checks from rate limiting: an orchestrator probe is not a business endpoint, so a rule written for business endpoints gets a narrow, explicit carve-out rather than a fabricated error response just to satisfy it. See "Automatically checked by the harness" below for how this carve-out is scoped mechanically (by the handler's own `@Router` path, not by its file/function name).

---

## Regenerating — `swag init`

The CLI is tracked as a Go **tool dependency** (Go 1.24+'s `tool` directive in `go.mod`, the modern replacement for the old `tools.go` blank-import idiom) rather than installed ad hoc, so every contributor/CI run resolves the exact same pinned `swag` version from `go.sum`:

```bash
cd examples
go get -tool github.com/swaggo/swag/cmd/swag@v1.16.6   # one-time — already done, tracked in go.mod's `tool` line

go tool swag init -g cmd/server/main.go --output docs --parseInternal
```

Run this after changing any `@`-annotation (on a handler or on `main()`) or any DTO field/doc comment. It is deterministic — running it twice in a row with no source change produces byte-identical output (verified when this convention was introduced) — which is what makes the CI freshness check below possible.

---

## Generated `docs/` is committed, not gitignored

Unlike some Go codegen conventions, `docs/docs.go`/`docs/swagger.json`/`docs/swagger.yaml` are committed to git, for one concrete reason: `cmd/server/main.go` blank-imports the generated `docs` package, so **the app does not compile without it present**. Gitignoring it would mean a fresh clone's `go build ./...` fails until someone remembers to run `swag init` first — inconsistent with this repository's general "clone and build" expectation, and with `go.sum`/`migrations/*.sql` already being committed generated-or-generated-adjacent artifacts. The tradeoff (committed generated code can drift from the annotations that produced it) is closed by a CI step, not by hand discipline — see below.

---

## Wired into CI — `.github/workflows/go.yml`

```yaml
- name: Verify OpenAPI docs are up to date (swag init)
  working-directory: implementations/go/examples
  run: |
    go tool swag init -g cmd/server/main.go --output docs --parseInternal
    git diff --exit-code -- docs || {
      echo '::error::implementations/go/examples/docs/ is stale — a handler @-annotation changed without re-running `go tool swag init -g cmd/server/main.go --output docs --parseInternal`.'
      exit 1
    }
```

This one step does double duty: `swag init` itself fails the build if an annotation doesn't parse (a malformed `@Param`/`@Success` line, a `{object}` type that doesn't resolve, ...), and the `git diff --exit-code` afterward fails the build if the committed `docs/` is stale relative to the current annotations — the same "commit generated code, verify freshness in CI" idiom Go projects commonly use for `go generate` output.

---

## Serving it — `router.go`

```go
// internal/interface/http/router.go — actual code (excerpt)
mux.HandleFunc("GET /health/live", healthHandler.Live)
mux.HandleFunc("GET /health/ready", healthHandler.Ready)
// Swagger UI + the generated OpenAPI JSON/YAML — public, unauthenticated, and
// (like the healthcheck routes) not wrapped by the rate limit middleware,
// since it's documentation rather than a business endpoint.
mux.Handle("GET /docs/", httpSwagger.WrapHandler)
```

`GET /docs/index.html` serves the Swagger UI, `GET /docs/doc.json` serves the raw generated spec — both public and outside the rate-limit/auth middleware chain, the same treatment `/health/*` already gets and the same treatment nestjs's `SwaggerModule.setup('docs', app, ...)` gets (no `AuthGuard` applied).

---

## Automatically checked by the harness

Whether every handler actually carries complete annotations (not just some of them, and not just for the success case) is automatically checked by `implementations/go/harness/api_documentation.go` (the `api-documentation` rule) — it finds every function/method under `internal/interface/**/*.go` matching net/http's handler signature `func(http.ResponseWriter, *http.Request)` (structural, not name-based, so it can't miss a handler because of an unusual name), and fails if that handler has no `@Summary`/`@Description`, or has no `@Failure` documenting a non-2xx response. The one exemption — `/health/*` routes don't require `@Failure` — is detected from the handler's own `@Router` annotation, not hardcoded to a specific file or function name.

### Related documents

- [api-response.md](../../../../docs/architecture/api-response.md) — the framework-agnostic completeness bar ("Machine-readable API documentation (OpenAPI)" section) this convention implements
- [error-handling.md](error-handling.md) — the sentinel-error → HTTP-status-code mapping table every `@Failure` line is cross-checked against
- [rate-limiting.md](rate-limiting.md) — the same "not a business endpoint" reasoning behind the `/health/*` carve-out
- `harness/README.md` — the `api-documentation` rule's full description

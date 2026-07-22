# Core Design Principles Summary (Go)

A TL;DR list that condenses what the other 26 documents in this repository cover. Each item is not a newly invented rule but an index into content the corresponding document already explains — follow the links in parentheses for detailed rationale, code, and known gaps.

1. **Fixed layer direction**: `Interface → Application → Domain`, and `Infrastructure` inverts the dependency by implementing interfaces declared by `Domain`. The `import` graph is the dependency direction ([layer-architecture.md](layer-architecture.md)).
2. **Domain packages are framework-agnostic**: `internal/domain/account/` imports only standard-library base packages plus a minimal dependency (`google/uuid`). It doesn't use even `log`, `net/http`, or `context` ([layer-architecture.md](layer-architecture.md), [cross-cutting-concerns.md](cross-cutting-concerns.md)).
3. **Invariants are validated only inside Aggregate Root methods.** Application Handlers are responsible only for orchestration — fetch → call domain method → save ([tactical-ddd.md](tactical-ddd.md)).
4. **Repositories keep the interface in the domain package and the implementation in the infrastructure package.** The implementation carries a compile-time satisfaction check via `var _ Repository = (*Impl)(nil)` ([repository-pattern.md](repository-pattern.md)).
5. **Errors are typed as sentinel errors** (`var ErrXxx = errors.New(...)`). They're wrapped with `fmt.Errorf("...: %w", err)` as they cross layers, and HTTP status code conversion happens only in the Interface layer via `errors.Is` ([error-handling.md](error-handling.md)).
6. **CQRS is expressed as `struct + Handle(ctx, cmd/query) (result, error)`.** There's no Command Bus/Query Bus — `router.go` wires everything together directly via constructors ([cqrs-pattern.md](cqrs-pattern.md)).
7. **Aggregate IDs are 32-character hex strings**, a UUID v4 with hyphens stripped (`common.NewID()` — [aggregate-id.md](aggregate-id.md)).
8. **There is no DI container** — every dependency is assembled via constructor functions (`New...`), and `main.go`/`router.go` wire them together in order. New dependencies are expressed by adding constructor arguments ([module-pattern.md](module-pattern.md), [bootstrap.md](bootstrap.md)).
9. **`context.Context` runs through every layer boundary** — cancellation, deadlines, correlation ID, and transactions all propagate explicitly as arguments. There's no hidden storage like Node's `AsyncLocalStorage` ([cross-cutting-concerns.md](cross-cutting-concerns.md), [persistence.md](persistence.md)).
10. **Cross-cutting concerns are handled by a middleware chain** (composition of `func(http.Handler) http.Handler`). Authentication/logging/correlation ID are separated outside the Handler so each middleware owns exactly one concern ([cross-cutting-concerns.md](cross-cutting-concerns.md)).
11. **Calls to other Bounded Contexts are wrapped in an Adapter**: the interface lives in the calling package and the implementation in the calling side's infrastructure — another domain's Repository/Service is never injected directly ([cross-domain.md](cross-domain.md)).
12. **Naming follows fixed rules**: files use `snake_case.go`, package names are a single lowercase word, types use `PascalCase`, and interfaces prefer role nouns (`Repository`, `AccountAdapter`) over verb+er ([directory-structure.md](directory-structure.md)).
13. **Encapsulation is only possible at the package level** — Go has no instance-level `private`, so any type that needs to be hidden from the outside must be split into a separate package from the start ([tactical-ddd.md](tactical-ddd.md)).
14. **Tests are split into 3 tiers**: Domain (table-driven, verifies pure functions) / Application (manual stub mocks) / E2E (`testcontainers-go`, real DB/SES integration). All 3 tiers are implemented under `examples/` ([testing.md](testing.md)).

---

### Where this list fits

This document is a "TL;DR index" for each of the other 26 documents — it doesn't invent new rules, it summarizes on a single page what the other documents already ground in code. For the actual code or known gaps behind a specific item, always follow the link in parentheses and read the source document.

# Module Pattern (Go) — Dividing boundaries with packages instead of a DI container

Go-specific document — Go has no concept corresponding to the root's and NestJS's "module" (`@Module`, DI container, `providers`/`exports`). **Go has no DI container at all.** This document covers the two actual mechanisms this repository uses to fill that gap: (1) manual constructor assembly in `main.go`, and (2) the package tree as the Bounded Context boundary. The directory layout itself (which file lives where) is already covered by [directory-structure.md](directory-structure.md), so it isn't repeated here — this document focuses on "how it's assembled."

---

## Who does what NestJS's `@Module` did, in Go

| NestJS `@Module` | Go equivalent |
|---|---|
| `providers: [Service, { provide: Repo, useClass: RepoImpl }]` | Calling `New...()` constructors in order from `main.go`/`router.go` |
| `imports: [OtherModule]` | Importing another package and using the types/functions it exports (capitalized names) |
| `exports: [Service]` | Go's exported identifiers (capitalized) are always accessible from outside the package — there's no separate "exports list" declaration |
| The DI container resolves the graph at runtime | The compiler statically checks the `import` graph — a cycle makes **the build itself fail** |
| Working around circular dependencies with `forwardRef()` | Doesn't exist — see the "Circular dependencies" section below |

The key difference: NestJS has a separate declarative layer called "module declarations," and a DI container resolves it at runtime. Go has no such layer — **`import` statements plus constructor calls are the wiring itself.**

---

## Mechanism 1 — manual constructor assembly in `main.go`

```go
// cmd/server/main.go (actual code)
accountRepo := persistence.NewAccountRepository(db)
notifier := notification.NewService(notification.NewSESClient(), db)
mux := httphandler.NewRouter(accountRepo, notifier)
```

Each `New...()` call corresponds to one entry in NestJS's `providers` array, but instead of a declaration, it's **executable code**. The assembly order must exactly match the dependency direction — code that calls `NewRouter(accountRepo, ...)` before `accountRepo` is created simply won't compile (since the variable hasn't been declared yet). This repository delegates assembly responsibility hierarchically: `main.go` → `router.go` → individual Handler constructors. See [bootstrap.md](bootstrap.md) for the full flow and goals (config validation + graceful shutdown integration).

**As more domains are added**, `main.go` grows by creating per-domain Infrastructure and calling each domain's `NewRouter` (or a function that registers routes on a shared `mux`) — in Go, the role NestJS's `AppModule.imports = [OrderModule, UserModule, PaymentModule]` played as "root composition" is replaced by `main.go` calling each domain's assembly function in order.

```go
// main.go assuming multiple domains
accountRouter := accounthttp.NewRouter(accountRepo, notifier)
userRouter := userhttp.NewRouter(userRepo)

mux := http.NewServeMux()
mux.Handle("/accounts/", accountRouter)
mux.Handle("/users/", userRouter)
```

---

## Mechanism 2 — the package tree stands in for Bounded Context boundaries

NestJS has an explicit unit: "1 Bounded Context = 1 `@Module`." In Go, that unit is the **package tree**: one domain is the set formed by `internal/domain/<domain>/` together with the corresponding files in `internal/application/{command,query}/` and `internal/infrastructure/<concern>/` that handle that domain.

```
internal/
  domain/
    account/              ← Account Bounded Context (domain rules)
  application/
    command/
      create_account_handler.go   ← Account-related Command
      ...
    query/
      get_account_handler.go      ← Account-related Query
  infrastructure/
    persistence/
      account_repository.go       ← Account Repository implementation
    notification/
      service.go                  ← Account notification implementation
```

As described in [directory-structure.md](directory-structure.md), this repository puts **the layer at the top level and the domain underneath it** (the opposite of NestJS). With only one domain so far this structure looks flat, but once a second domain (e.g. User) is added, "which Bounded Context does this file belong to" is determined **not by directory location but by which domain package the file imports** — if `application/command/create_user_handler.go` imports `internal/domain/user`, that means it belongs to the User BC. As more domains accumulate, consider subdividing with subdirectories/filenames such as `command/<domain>/` or `persistence/<domain>_repository.go` (see directory-structure.md).

**The concept corresponding to "a Service a module exports"**: NestJS requires an explicit `exports: [UserService]` before another module can have that Service injected. Go has no such declaration — an exported (capitalized) type/function in the `internal/domain/user` package can be imported and used from anywhere within the same `internal/` tree. A type you want to hide as "internal only" must be made an unexported (lowercase) identifier, or split into a separate, more inward package entirely (see the "Encapsulation limits" section of [tactical-ddd.md](tactical-ddd.md)) — since Go has no instance-level `private`, this is the only means of hiding available.

---

## Circular dependencies — Go has no workaround

If two NestJS modules need each other, `forwardRef(() => OtherModule)` can make the cycle "compile for now." **Go has no such workaround** — if package A imports package B and B imports A, `go build` fails immediately, like this:

```
import cycle not allowed
	package github.com/example/account-service/internal/domain/account
		imports github.com/example/account-service/internal/domain/user
		imports github.com/example/account-service/internal/domain/account
```

This is not a limitation but an **advantage**: NestJS's `forwardRef()` hides the signal that "there's a design problem," while Go surfaces that signal immediately as a compile error. When a cycle occurs, resolve it with one of the following.

1. **Extract the shared concept into a third package** — move the type/interface that both A and B reference into a separate package such as `internal/domain/shared` (or a common parent concept of the two domains), and have both A and B import only that package.
2. **Force the direction to be one-way with an Adapter** — if it looks like two domains need to call each other, the design can often be rearranged so that one side owns an Adapter to the other (see [cross-domain.md](cross-domain.md)). "A calls B and B also calls A" is usually a sign that the boundary between the two BCs was drawn incorrectly.
3. **Consider switching to async** — if bidirectional synchronous calls are genuinely necessary, eliminate the cycle itself by converting one or more sides to an Integration Event (async), per the criteria in the root [cross-domain-communication.md](../../../../docs/architecture/cross-domain-communication.md).

In every case, Go doesn't allow "work around it for now, fix it later" — the moment the compiler blocks a cycle is exactly the moment the design needs to be reconsidered.

---

## Principles

- **Don't mimic a DI container** — don't try to implement a reflection-based container or service locator yourself; accept constructor chaining as it is.
- **Package = encapsulation boundary**: to hide a type internal to a domain, use an unexported identifier or split it into a separate package. There is no "hide it via a module declaration" option in Go.
- **Treat circular dependencies as an immediate redesign signal** — don't try to work around the compile error.
- **Subdivide packages as domains grow** — split into things like `command/<domain>/` or `persistence/<domain>_repository.go` when the need arises, not preemptively.

---

### Related documents

- [directory-structure.md](directory-structure.md) — the actual package tree layout and naming rules
- [bootstrap.md](bootstrap.md) — the full assembly order in `main.go`
- [layer-architecture.md](layer-architecture.md) — dependency direction between layers
- [cross-domain.md](cross-domain.md) — a concrete example of wrapping cross-domain calls in an Adapter to avoid cycles
- [tactical-ddd.md](tactical-ddd.md) — the limits of package-level encapsulation

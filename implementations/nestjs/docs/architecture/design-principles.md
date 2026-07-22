# Core Design Principles Summary

1. **Domain-first directory structure** — the 4 layers domain/application/interface/infrastructure placed under `src/<domain>/`
2. **The Domain layer is framework-independent** — pure TypeScript. NestJS decorators (@Injectable, etc.) are prohibited
3. **Business rules are encapsulated in the Aggregate Root** — the Application Service only coordinates
4. **One Repository per Aggregate Root** — an abstract class in the domain layer, an implementation in the infrastructure layer
5. **The Repository is injected via NestJS DI** — the `{ provide: AbstractClass, useClass: ImplClass }` pattern
6. **Repository queries use only a single `find<Noun>s`** — for a single record, `take: 1` + `.then(r => r.<noun>s.pop())`
7. **An update method on the Repository is prohibited** — modify via the Aggregate's domain method, then `save<Noun>`
8. **The Mapping Table is accessible from both domains** — the Service handles orchestration of work across domains
9. **save/delete handle cascading to connected entities** — the Service only calls a domain-level method
10. **An Interface DTO = a thin wrapper around an Application object** — no logic, just extends
11. **Errors are typed as an enum** — free-form strings are prohibited
12. **The Controller converts the error type → an HTTP exception** — using the `generateErrorResponse` utility
13. **Throwing HttpException in Domain/Service is prohibited** — use only a plain Error

# Core Design Principles Summary — Kotlin Spring Boot

This combines and reconfirms content already covered across this repository's other 21 documents. Each item has detailed justification somewhere in another document — no new rules are created here.

1. **Domain-first directory structure** — the 4-layer `<domain>/{domain,application,infrastructure,interfaces}/`. The top-level package boundary is the Bounded Context, not the layer ([directory-structure.md](directory-structure.md)).

2. **The Domain layer has no framework dependency (neither Spring nor JPA)** — not just `@Service`/`@Component`/`@Repository`/`@Controller`, but also `jakarta.persistence` (`@Entity`/`@Embeddable`/`@Column`, etc.) is forbidden in domain/. JPA mapping is handled entirely by `AccountJpaEntity`/`TransactionJpaEntity`/`MoneyEmbeddable` + `AccountMapper`/`TransactionMapper` in `infrastructure/persistence/`. The harness's `domain-purity` check enforces both (Spring stereotypes + `jakarta.persistence` imports in domain/) ([directory-structure.md](directory-structure.md), [layer-architecture.md](layer-architecture.md)).

3. **Aggregate creation is controlled via a `protected constructor()` + `companion object.create()` factory** — external code cannot create an empty instance with `Account()`, and the sole public creation path validates the invariants ([tactical-ddd.md](tactical-ddd.md)).

4. **Every Aggregate/Entity property has `private set`** — state changes only happen through domain methods (`deposit()`, `suspend()`, etc). The compiler enforces "no direct external assignment" ([tactical-ddd.md](tactical-ddd.md)).

5. **Value Objects and events are `data class`** — the compiler generates `equals()`/`hashCode()`/`copy()`. Invariants are validated immediately in an `init {}` block (`Money`, [tactical-ddd.md](tactical-ddd.md)).

6. **"Not found" is `T?`, not `Optional<T>`** — expressed with a nullable type + the `?:` Elvis operator. The compiler blocks missing null checks, enforcing the domain invariant ("a value must exist after a successful lookup") through the type system ([layer-architecture.md](layer-architecture.md), [repository-pattern.md](repository-pattern.md)).

7. **Errors are typed via a `sealed class` hierarchy** — since only subclassing within the same file is allowed, the compiler knows every subtype, and checks the exhaustiveness of `when` branches at compile time. Free-form string exceptions are forbidden (`AccountException`, [error-handling.md](error-handling.md)).

8. **Constructor injection, no `@Autowired` needed** — primary constructor parameters are the DI target as-is. If there's only one constructor, Spring recognizes it automatically ([layer-architecture.md](layer-architecture.md), [module-pattern.md](module-pattern.md)).

9. **For a Repository/Adapter, the `interface` itself is the DI token** — no `abstract class` or separate binding syntax is needed like in Java/TypeScript. Spring auto-injects the sole implementation on the classpath ([repository-pattern.md](repository-pattern.md), [cross-domain.md](cross-domain.md)).

10. **Repository queries are unified into a single `find<Noun>s`, with single-item lookups done via `take: 1` + `.firstOrNull()`** — dedicated single-item lookup methods (like the old `findByAccountIdAndOwnerId`) are never added. `AccountRepository.findAccounts()` actually implements this principle ([repository-pattern.md](repository-pattern.md)).

11. **The Repository never has an update method** — state changes are performed via an Aggregate domain method, then persisted with `saveAccount` (`save<Noun>`) ([repository-pattern.md](repository-pattern.md)).

12. **Command/Query Service separation, Query uses `@Transactional(readOnly = true)`** — Hibernate skips dirty checking, optimizing read performance ([cqrs-pattern.md](cqrs-pattern.md), [layer-architecture.md](layer-architecture.md)).

13. **Per-concern configuration is `@ConfigurationProperties` + `data class`** — a field with no default value is itself the fail-fast validation. `@Value("${...}")` is never scattered across service code ([config.md](config.md)).

14. **Configuration values are only injected in the Infrastructure layer** — a `@ConfigurationProperties` type is never injected directly into a Domain/Application Service constructor ([config.md](config.md)).

15. **The Domain layer never imports any cross-cutting concern (Filter/Interceptor/Logger)** — logging and Correlation ID are only handled at the Application layer or above ([cross-cutting-concerns.md](cross-cutting-concerns.md)).

### Related documents

Why each item on this list is the way it is, and what gaps the current `examples/` still hasn't reflected, are covered in detail in the document linked next to each principle. New code is written against **the pattern the documents define**, not against `examples/`'s actual code as-is — this is likewise stated repeatedly in the "known gaps" table of `docs/implementations/kotlin-springboot.md`.

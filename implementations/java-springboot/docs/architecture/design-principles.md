# Core Design Principles Summary (Spring Boot)

This is a condensed TL;DR index of the most important rules that this repository's Java Spring Boot implementation follows. For the detailed rationale and example code for each item, see the linked document — no new rules are introduced here.

1. **Domain-based package structure** — split into four subpackages: `<domain>/{domain,application,infrastructure,interfaces}/`. `interfaces` is plural (to avoid the Java reserved word `interface`). ([directory-structure.md](directory-structure.md))

2. **The Domain layer must be framework-independent** — `Account`/`Transaction`/`Money` (domain) are plain objects that do not import `jakarta.persistence.*`. JPA mapping is handled entirely by `AccountJpaEntity`/`TransactionJpaEntity`/`MoneyEmbeddable` (infrastructure), with `AccountMapper`/`TransactionMapper` performing the conversion. ([layer-architecture.md](layer-architecture.md))

3. **Aggregates are created only through static factories** — no public constructor is exposed; instead there is `protected Account() {}` + `static Account create(...)`. Child entities (`Transaction`) use a `static` **package-private** creation method to block, at compile time, any direct creation that bypasses the Aggregate Root. ([tactical-ddd.md](tactical-ddd.md))

4. **Value Objects are represented as `record` with a compact canonical constructor** — validation always runs on construction, and since fields are `final`, operations always return a new instance (`Money.add()`). ([tactical-ddd.md](tactical-ddd.md))

5. **Repositories exist only at the Aggregate Root level** — interfaces live in `domain/`, and implementations (`<Aggregate>RepositoryImpl`) live in `infrastructure/persistence/`. Spring Data `JpaRepository` (`<Aggregate>JpaRepository`) is used only inside the implementation and is never exposed to Domain/Application. ([repository-pattern.md](repository-pattern.md))

6. **Dependency injection uses constructor injection only — `@Autowired` field injection is forbidden** — Lombok `@RequiredArgsConstructor` generates a constructor over `final` fields. For a class with exactly one constructor, Spring auto-wires it without needing `@Autowired`. Every Service/Repository/Controller in `examples/` follows this pattern. ([module-pattern.md](module-pattern.md))

7. **`@Transactional` is placed on the `Repository`'s `save*()` methods — never on the Command/Query Service itself** — this is exactly the boundary where saving the Account and writing to the Outbox must be combined into a single physical transaction. Re-adding `@Transactional` to a Command Service (`WithdrawService`, `TransferService`, etc.) is a regression. Writes use `@Transactional`, and reads use `@Transactional(readOnly = true)` to reduce Hibernate dirty-checking overhead. Side work that must be isolated from the original transaction boundary (e.g. sending notifications) is separated using `Propagation.REQUIRES_NEW`. ([persistence.md](persistence.md), [layer-architecture.md](layer-architecture.md))

8. **Command/Query Services are separated, and Query must use a dedicated read interface** — both `GetAccountService`/`GetTransactionsService` depend on a narrow `AccountQuery` (application/query), not the write-side `AccountRepository`. The harness's `cqrs-query-purity` rule automatically catches any direct Repository reference from files under `application/query/`. ([cqrs-pattern.md](cqrs-pattern.md))

9. **Errors are always thrown as typed exceptions + an `ErrorCode` enum** — free-form strings are forbidden. `AccountException(ErrorCode, message)` is thrown immediately inside Domain methods, and only the Interface layer's `@ExceptionHandler` (Controller or `@RestControllerAdvice`) converts it into an HTTP response. Domain/Application never reference `HttpStatus`/`ResponseEntity`. ([error-handling.md](error-handling.md))

10. **Error responses consist of 4 fields (`statusCode`/`code`/`message`/`error`)** — `ErrorResponse` carries all four fields, so clients can determine the HTTP status from the response body alone. ([error-handling.md](error-handling.md))

11. **Interface DTOs are thin wrappers over Application objects** — a one-line conversion that simply carries `record` fields over (`new DepositCommand(accountId, requesterId, request.amount())`) is sufficient. A mapping library like MapStruct is unnecessary until the field count grows large. ([layer-architecture.md](layer-architecture.md))

12. **Configuration access is confined to the Infrastructure layer** — `@Value`/`@ConfigurationProperties` injection targets are limited to `@Configuration`/`@Component` classes; Application Services and Domain never reference configuration values directly. `@ConfigurationProperties` + `@Validated` perform fail-fast validation at startup — every field of `AwsProperties`/`SesProperties` (`region`/`accessKeyId`/`secretAccessKey`/`senderEmail`) is validated with `@NotBlank`. ([config.md](config.md))

13. **Cross-BC calls happen only through an Adapter (synchronous) or an Integration Event (asynchronous)** — another BC's Service/Repository is never injected directly into the Application layer. Reads go through an interface in `application/adapter/` with its implementation in `infrastructure/`; state changes propagate only via Outbox-based Integration Events. ([cross-domain.md](cross-domain.md), [cross-domain-communication.md](../../../../docs/architecture/cross-domain-communication.md))

---

The documents (`docs/architecture/`) reflect the 13 principles above, and the actual code in `examples/` follows them as well. For the remaining gap list, see the coverage table in [docs/implementations/java-springboot.md](../../../../docs/implementations/java-springboot.md).

---

### Related documents

This document is a summary index. The full rationale, example code, and trade-off discussion for each principle are in the individual documents linked above.

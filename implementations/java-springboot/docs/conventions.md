# Coding Conventions (Java Spring Boot)

> For framework-agnostic principles, see the root [conventions.md](../../../docs/conventions.md). This document is rewritten in Java/Spring idiom — while the Kotlin Spring Boot implementation and Spring's mechanics themselves overlap, naming/typing expressions follow each language's own idiom (see [java-springboot/CLAUDE.md](../CLAUDE.md)).

## 1. File naming rules

- All file names: `PascalCase`, **file name = public class name** (a Java compiler requirement)
- Package names: lowercase, no hyphens/underscores — `com.example.accountservice.account.domain`
- Aggregate Root: `<Aggregate>.java` (`domain/`) — e.g. `Account.java`
- Child Entity: `<Entity>.java` (`domain/`) — e.g. `Transaction.java`
- Value Object: `<Concept>.java`, a `record` (`domain/`) — e.g. `Money.java`
- Domain Event: `<DomainEvent>.java`, a `record`, past tense (`domain/`) — e.g. `MoneyDepositedEvent.java`
- Repository interface: `<Aggregate>Repository.java` (`domain/`)
- Repository implementation: `<Aggregate>RepositoryImpl.java` (`infrastructure/persistence/`)
- Spring Data JPA interface: `<Aggregate>JpaRepository.java` (`infrastructure/persistence/`)
- Command Service: `<Verb><Noun>Service.java` (`application/command/`) — e.g. `CreateAccountService.java`
- Query Service: `Get<Noun>Service.java` (`application/query/`) — e.g. `GetAccountService.java`
- Query interface (once introduced): `<Aggregate>Query.java` (`application/query/`)
- Query implementation (once introduced): `<Aggregate>QueryImpl.java` (`infrastructure/persistence/`)
- Command: `<Verb><Noun>Command.java`, a `record` (`application/command/`)
- Result: `<Verb><Noun>Result.java`, a `record` (`application/{command,query}/`)
- Exception: `<Domain>Exception.java` (`domain/`) — defines a nested `ErrorCode` enum inside
- Domain Event Handler: `<DomainEvent>Handler.java`, implements `outbox.OutboxEventHandler` (`application/event/`) — e.g. `AccountCreatedEventHandler.java`
- Technical Service interface: `<Concern>Service.java` (`application/service/`) — e.g. `NotificationService.java`
- Technical Service implementation: `<Concern>ServiceImpl.java` (`infrastructure/`)
- Adapter interface: `<ExternalDomain>Adapter.java` (`application/adapter/`)
- Adapter implementation: `<ExternalDomain>AdapterImpl.java` (`infrastructure/`)
- HTTP Controller: `<Domain>Controller.java` (`interfaces/rest/`)
- Interface DTO (request): `<Verb><Noun>Request.java`, a `record` (`interfaces/rest/`) — e.g. `DepositRequest.java`
- Error response: `ErrorResponse.java`, a `record` (`interfaces/rest/`)
- `@ConfigurationProperties` class: `<Concern>Properties.java`, a `record` (`config/`) — e.g. `AwsProperties.java`
- `@Configuration` config class: `<Concern>Config.java` (in that domain's `infrastructure/` or shared `config/`) — e.g. `SesConfig.java`
- Pure utility: `<Concern>.java` (`common/`, no framework dependency) — e.g. `IdGenerator.java`

---

## 2. Class naming rules

- Aggregate Root: a domain noun — `Account`
- Child Entity: a domain noun — `Transaction`
- Value Object: a concept name — `Money`
- Status enum: `<Domain>Status` — `AccountStatus`
- Domain Event: past tense — `MoneyDepositedEvent`, `AccountClosedEvent`
- Repository interface: `AccountRepository` / implementation: `AccountRepositoryImpl`
- Spring Data JPA interface: `AccountJpaRepository`
- Command Service: `CreateAccountService`, `DepositService`, `SuspendAccountService`
- Query Service: `GetAccountService`, `GetTransactionsService`
- Command: `DepositCommand`, `CreateAccountCommand`
- Result: `CreateAccountResult`, `GetAccountResult`, `TransactionResult`
- Exception: `AccountException` (its inner `ErrorCode` enum uses `SCREAMING_SNAKE_CASE` constants — `ACCOUNT_NOT_FOUND`)
- Domain Event Handler: `AccountCreatedEventHandler`, `MoneyDepositedEventHandler`
- Technical Service interface: `NotificationService`, `SecretService`
- Adapter interface: `UserAdapter` (external BC name + `Adapter`)
- HTTP Controller: `AccountController`
- Interface DTO: `DepositRequest`, `CreateAccountRequest`
- Constants: `UPPER_SNAKE_CASE` — `MAX_TRANSACTIONS_PER_PAGE`
- Methods/fields: `camelCase` — `getAccountId()`, `pullDomainEvents()`
- Enum constants: `UPPER_SNAKE_CASE` — `AccountStatus.ACTIVE`

---

## 3. `interfaces` (plural) — avoiding a Java reserved word

`interface` is a Java language keyword, so it cannot be used as a package name. Where the root document uses `interface/` (singular), this repository uses `interfaces/` (plural) — the same kind of accommodation go/kotlin-springboot each make for their own language constraints. Be careful not to mistakenly write `interface/` when creating a new domain package.

```
com.example.accountservice.account.interfaces.rest   // correct
com.example.accountservice.account.interface.rest    // compile error — interface is a reserved word
```

---

## 4. Enum / error code placement rules

- **An enum representing status is defined as a separate file in domain/** — `AccountStatus.java`, `TransactionType.java`. It is not defined inline inside the Aggregate class.
- **Error codes are defined as a nested enum (`ErrorCode`) inside `<Domain>Exception`** — instead of the root's separate `<Domain>ErrorCode.ts` file, Java naturally cohesive-groups the exception class and its codes in one file (at every throw site, it is always statically referenced as `AccountException.ErrorCode.INSUFFICIENT_BALANCE` — a free-form string code is impossible from the start).

```java
// account/domain/AccountStatus.java — a separate file
public enum AccountStatus {
    ACTIVE, SUSPENDED, CLOSED
}

// account/domain/AccountException.java — the exception + nested ErrorCode
public class AccountException extends RuntimeException {

    public enum ErrorCode {
        ACCOUNT_NOT_FOUND,
        INVALID_AMOUNT,
        INSUFFICIENT_BALANCE,
        DEPOSIT_REQUIRES_ACTIVE_ACCOUNT
    }

    private final ErrorCode code;

    public AccountException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode code() { return code; }
}
```

- **Every value of `ErrorCode` must have an actual throw site somewhere** — don't leave a code defined but never used.

---

## 5. Typing patterns

### Value Object / Command / Result — `record`

```java
// Correct — a record automatically gives immutability + equals/hashCode
public record Money(long amount, String currency) {
    public Money {   // compact canonical constructor — validation applies to every construction path
        if (amount < 0) throw new AccountException(AccountException.ErrorCode.INVALID_MONEY_AMOUNT, "Amount must be 0 or greater.");
    }
}

public record DepositCommand(String accountId, String requesterId, long amount) {}
public record CreateAccountResult(String accountId, String ownerId, long balance, String currency) {}
```

```java
// Incorrect — expressing a Command/Result as a plain class with mutable fields
public class DepositCommand {
    private String accountId;
    public void setAccountId(String accountId) { this.accountId = accountId; }   // a setter exists — violates immutability
}
```

### Aggregate Root / child Entity — static factory + `protected` constructor

```java
// Correct
protected Account() {}   // leaves open only the no-arg constructor JPA requires

public static Account create(String ownerId, String email, String currency) {
    Account account = new Account();
    account.accountId = IdGenerator.generate();   // 32-character hex, no hyphens — see aggregate-id.md
    account.balance = new Money(0, currency);
    account.status = AccountStatus.ACTIVE;
    return account;
}
```

```java
// Incorrect — a public constructor lets you directly create an Account in an arbitrary state
public Account(String accountId, Money balance, AccountStatus status) { ... }
```

### `Optional<T>` — expressing the absence of a single-record lookup

The Repository does not have a separate single-record-only method (see repository-pattern.md) — call `findAccounts` with `take: 1`, then get an `Optional` via `Stream.findFirst()`:

```java
// Correct — get an Optional via take:1 + findFirst() to express absence
Account account = accountRepository
        .findAccounts(new AccountFindQuery(0, 1, accountId, requesterId, null))
        .accounts().stream().findFirst()
        .orElseThrow(() -> new AccountException(AccountException.ErrorCode.ACCOUNT_NOT_FOUND, "Account not found."));
```

```java
// Incorrect — returning null directly forces every caller to repeat a null check
Account findFirstOrNull(AccountFindQuery query);   // may return null
```

### `var` usage scope — limited to local variables

```java
// Allowed — a local variable whose right-hand-side type is clear
var qb = accountRepo.createQueryBuilder("account");

// Forbidden — do not use var for fields, method parameters, or return types (Java doesn't allow this to begin with, but stated explicitly)
```

### Lombok usage scope

- **`@RequiredArgsConstructor`**: used on every class that needs constructor injection — Application Services, Repository implementations, Technical Service implementations, etc.
- **Lombok is not used in the Domain layer (Aggregate, Value Object, Domain Event)** — a `record` already provides immutability/`equals`/`toString`, and an Aggregate needs special construction logic (static factories, invariant validation), so general-purpose annotations like `@AllArgsConstructor`/`@Data` could open up a constructor that bypasses invariants.
- **Do not attach `@Getter`/`@Setter` to an Aggregate Root** — `@Setter` breaks encapsulation and allows state changes that bypass domain methods. If a getter is needed, write it by hand or selectively use only `@Getter`.

```java
// Correct — an Application Service
@Service
@RequiredArgsConstructor
@Transactional
public class CreateAccountService {
    private final AccountRepository accountRepository;
}

// Forbidden — @Data/@Setter on the Domain layer
@Entity
@Data   // forbidden — equals/hashCode/setters can bypass invariants
public class Account { ... }
```

---

## 6. REST API endpoint design rules

### URL structure — resource-centric, plural nouns

A URL represents a **resource (noun), not an action (verb)**. The HTTP method expresses the action.

```
// Correct
GET    /accounts                     list accounts
GET    /accounts/{accountId}         get a single account
POST   /accounts                     open an account
POST   /accounts/{accountId}/deposit deposit (a non-CRUD action — a sub-resource path)

// Incorrect
GET    /getAccounts        don't put a verb in the URL
POST   /createAccount      don't put a verb in the URL
GET    /account/{id}       don't use the singular form — always plural
```

### HTTP methods and response codes — `@ResponseStatus`

| Method | Purpose | Success code | Spring expression |
|--------|------|----------|-------------|
| `GET` | Fetch a resource | 200 OK | Default (no annotation needed) |
| `POST` (create) | Create a resource | 201 Created | `@ResponseStatus(HttpStatus.CREATED)` |
| `POST` (state transition) | Change state, no response body | 204 No Content | `@ResponseStatus(HttpStatus.NO_CONTENT)` |
| `PUT` | Replace a resource entirely | 200 OK | Default |
| `PATCH` | Partially update a resource | 200 OK | Default |
| `DELETE` | Delete a resource | 204 No Content | `@ResponseStatus(HttpStatus.NO_CONTENT)` |

```java
// Correct
@PostMapping
@ResponseStatus(HttpStatus.CREATED)
public CreateAccountResult createAccount(@Valid @RequestBody CreateAccountRequest request) { ... }

@PostMapping("/{accountId}/suspend")
@ResponseStatus(HttpStatus.NO_CONTENT)
public void suspendAccount(@PathVariable String accountId) { ... }
```

### Non-CRUD actions — sub-resource paths

```
POST /accounts/{accountId}/deposit     deposit
POST /accounts/{accountId}/withdraw    withdraw
POST /accounts/{accountId}/suspend     suspend the account
POST /accounts/{accountId}/reactivate  reactivate the account
POST /accounts/{accountId}/close       close the account
```

### Listing — pagination

```java
@GetMapping("/{accountId}/transactions")
public GetTransactionsResult getTransactions(
        @PathVariable String accountId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int take
) { ... }
```

```
GET /accounts/{accountId}/transactions?page=0&take=20
```

- `page`: starts at 0. `take`: the page size. Spring MVC automatically handles string → primitive-type conversion via `@RequestParam(defaultValue = ...)` — no separate parsing code is needed.
- The list response's key name must be the domain object's name, plural (`transactions`) — generic keys like `data`/`result`/`items` are forbidden.

### URL naming rules

- **Plural nouns**: `/accounts`, `/transactions` (singular is forbidden)
- **kebab-case**: multi-word resources are separated with hyphens (e.g. `/payment-methods`)
- **Lowercase only**: `/Accounts` (wrong) → `/accounts` (right)
- **No trailing slash**, **no file extension**

---

## 7. Method naming and organization

### Controller methods

- Use verbs that reveal the action: `create`, `deposit`, `withdraw`, `suspend`, `reactivate`, `close`, `get`, `getTransactions`, etc.
- The return type is the Application Result record used as-is (it is not wrapped in a separate Response class — see "Interface DTO — a thin wrapper" in `layer-architecture.md`)
- No logic — only Command/Query conversion + delegation to the Service

```java
// Correct
@PostMapping("/{accountId}/deposit")
@ResponseStatus(HttpStatus.CREATED)
public TransactionResult deposit(
        @PathVariable String accountId,
        @RequestBody DepositRequest request
) {
    return depositService.deposit(new DepositCommand(accountId, requesterId, request.amount()));
}
```

### Application Service method ordering

1. `private final` fields (Lombok's `@RequiredArgsConstructor` generates the constructor)
2. Public use-case methods
3. Private helper methods (if any)

### Repository method naming

- Query: unified into a single `find<Noun>s` (plural) — a single-record lookup also reuses this method via `take: 1` + `Stream.findFirst()` (see `repository-pattern.md`). Spring Data derived queries (`findBy...`) are used only at the `AccountJpaRepository` (infrastructure-internal-only) level.
- Save: `save<Noun>` (e.g. `saveAccount`)
- Delete: `delete<Noun>` — implemented as a soft delete (see `repository-pattern.md`)
- No `update<Noun>` method is provided — fetch, change state via an Aggregate domain method, and save via `save<Noun>`.

### Aggregate domain methods

- Verb form — `deposit()`, `withdraw()`, `suspend()`, `reactivate()`, `close()`
- A return type only when needed (e.g. `deposit()`, which returns the created child-Entity result) — otherwise mostly `void`
- On an invariant violation, throw immediately near the start of the method (guard-clause style)

---

## 8. import / package organization patterns

### import group order — standard Java convention

```java
// 1. java.* / javax.* / jakarta.*
import java.time.LocalDateTime;
import java.util.List;
import jakarta.persistence.Entity;

// 2. Third-party (org.springframework.*, lombok.*, etc)
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;

// 3. Project-internal (com.example.accountservice.*)
import com.example.accountservice.account.domain.Account;
import com.example.accountservice.account.domain.AccountRepository;
```

- The IDE's (IntelliJ) default import sorting applies this order automatically — a wildcard import (`import com.example.accountservice.account.application.command.*`) is only allowed as an exception when referencing many classes in the same package at once (`AccountController` actually uses this pattern).
- **Java has no concept of a relative path at all** — only a package declaration (`package com.example.accountservice.account.domain;`) and a fully-qualified import path exist, so the root's "no relative-path imports" rule is automatically satisfied in Java.

### Package = Bounded Context boundary

```
com.example.accountservice/
  account/     ← contains all 4 layers: domain/application/infrastructure/interfaces (the notification Technical Service is inside this too)
  common/      ← shared utilities/components (see shared-modules.md)
  AccountServiceApplication.java
```

Top-level packages are not split by layer (`controllers/`, `services/`) — they are split by domain (BC).

---

## 9. Swagger / OpenAPI documentation pattern — springdoc-openapi

`build.gradle` has the `springdoc-openapi-starter-webmvc-ui` dependency (see `bootstrap.md`).

```groovy
// build.gradle — actual code
implementation 'org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.3'
```

```java
// Correct — springdoc annotations on record fields
public record CreateAccountRequest(
        @Schema(description = "The account owner's email address.", example = "owner@example.com") @NotBlank @Email String email,
        @Schema(description = "The ISO 4217 currency code the account is opened in.", example = "KRW") @NotBlank String currency
) {}
```

```java
// Controller method — @Operation for a summary+description, one @ApiResponse per status code
// the handler can actually return (cross-checked against its @ExceptionHandler's error-mapping,
// not just the success response — see the root api-response.md "Machine-readable API
// documentation (OpenAPI)" section for the exact completeness bar)
@Operation(summary = "Open a new account", description = "Opens a new account for the authenticated requester with a 0 balance in the given currency.")
@ApiResponse(responseCode = "201", description = "The account was created.", content = @Content(schema = @Schema(implementation = CreateAccountResult.class)))
@ApiResponse(responseCode = "400", description = "Request validation failed (`VALIDATION_FAILED`) — e.g. an invalid email or missing currency.", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
@PostMapping
@ResponseStatus(HttpStatus.CREATED)
public CreateAccountResult createAccount(@Valid @RequestBody CreateAccountRequest request) { ... }
```

- **DTO validation is expressed via Bean Validation** (`jakarta.validation.constraints.*`) — `@NotBlank`, `@Email`, `@Min`, `@Max`, etc. This corresponds to NestJS's `class-validator` decorators.
- springdoc reads `@RestController`/`@RequestMapping`/`@Valid` annotations via reflection and **automatically** exposes `/v3/api-docs`, `/swagger-ui.html` — unlike NestJS's `SwaggerModule.createDocument()`, there's no need to assemble a document object in `main()` (see `bootstrap.md`).
- Customizing the Bearer auth scheme, title/version, etc. only requires a single `@Bean OpenAPI` in `config/OpenApiConfig` (a `@Configuration` class).
- A controller-wide error (e.g. a missing/invalid bearer token) is documented once at the class level via `@SecurityRequirement` + a class-level `@ApiResponse(responseCode = "401", ...)`, rather than repeating it on every method.
- `harness/src/rules/ApiDocumentation.java` (rule: `api-documentation`) mechanically fails the build if an operation's `@Operation` is missing a `summary`/`description`, or if no non-2xx `@ApiResponse` is documented for it (counting class-level `@ApiResponse`s too).

---

## 10. Logger pattern

### Declaration — fixed as a class field, SLF4J

```java
private static final Logger log = LoggerFactory.getLogger(NotificationServiceImpl.class);
```

### Structured logging — `StructuredArguments.kv(...)`

```java
// Correct — explicitly structure snake_case field names
import static net.logstash.logback.argument.StructuredArguments.kv;

log.info("Email sent", kv("account_id", accountId), kv("event_type", eventType), kv("ses_message_id", messageId));
```

```java
// Avoid — with only {} placeholders, a log collector can't index the fields individually
log.info("Email sent: accountId={}, eventType={}", accountId, eventType);
```

- The local profile branches to human-readable plain text (`%d{HH:mm:ss} %-5level %logger{36} - %msg%n`), and the production profile branches to JSON (a Logstash encoder) via `springProfile` (see `observability.md`).
- The Correlation ID is injected into the MDC by a `Filter`, and the JSON encoder automatically includes it via `includeMdcKeyName` — there's no need to pass correlationId as an argument on every log call.

### Logging criteria by layer

| Layer | What to log |
|---|---|
| Domain | **Forbidden** — no domain method imports a Logger |
| Application | Business events, results of external calls |
| Infrastructure | External-integration failures/retries |
| Interfaces | Request errors (`log.warn` on entry to `@ExceptionHandler`) |

---

## 11. Comment style

- Business-domain explanations are written as inline comments (`//`), in English.
- Javadoc (`/** ... */`) is used selectively, only for a **public API called by another BC/team** (an Adapter interface, etc) — it is not used for internal implementation details.
- A long method uses section comments to mark logical divisions.

```java
// Correct — an Adapter interface explains the contract via Javadoc
public interface UserAdapter {
    /**
     * Fetches the display name of the owner of accountId.
     * The User BC's internal structure is completely hidden behind this method signature.
     */
    Optional<UserSummary> findUser(String ownerId);
}

// Correct — inside a Service method, use // section comments
public CreateAccountResult create(CreateAccountCommand command) {
    // Create the Aggregate (invariants are validated in the constructor/factory)
    Account account = Account.create(command.ownerId(), command.email(), command.currency());
    // Save (including the Outbox, in the same transaction)
    accountRepository.saveAccount(account);
    return new CreateAccountResult(account.getAccountId(), account.getOwnerId(), account.getBalance().amount(), account.getBalance().currency());
}
```

---

## 12. Commit message convention

Follows the same [Conventional Commits](https://www.conventionalcommits.org/) spec as section 2 of the root [conventions.md](../../../docs/conventions.md) — being a Java Spring Boot implementation is no reason to use a different scheme.

### Message structure

```
<type>(<scope>): <description>

[optional body]

[optional footer(s)]
```

### type list

| type | Description | Example |
|------|------|------|
| `feat` | Add a new feature | `feat(account): add account suspension` |
| `fix` | Fix a bug | `fix(account): fix withdrawal succeeding despite insufficient balance` |
| `refactor` | Restructure code with no behavior change | `refactor(account): extract query logic into AccountQuery` |
| `docs` | Documentation-only change | `docs: add aggregate-id guide` |
| `test` | Add or modify tests | `test(account): add unit tests for account suspension invariants` |
| `chore` | Build, CI, dependencies, and other non-code-behavior work | `chore(deps): add springdoc-openapi dependency` |
| `style` | Code formatting, no behavior change | `style: clean up import order` |
| `perf` | Performance improvement | `perf(account): optimize the transaction-history query projection` |

### scope / description rules

- scope is the **service domain name** (`account`, `notification`, etc) or a non-code target (`ci`, `deps`, `docker`)
- description is a descriptive English phrase, with no trailing period

### BREAKING CHANGE

```
feat(account)!: change the account response schema

BREAKING CHANGE: GetAccountResult's balance field is split into balance.amount/balance.currency
```

---

## 13. Branch and PR conventions

Follows the same Conventional Branch scheme as section 3 of the root [conventions.md](../../../docs/conventions.md).

```
<type>/<scope>-<short-description>
```

| type | Purpose | Example |
|------|------|------|
| `feat` | New feature development | `feat/account-suspend` |
| `fix` | Bug fix | `fix/account-withdraw-balance-check` |
| `refactor` | Refactoring | `refactor/account-query-separation` |
| `docs` | Documentation change | `docs/aggregate-id-guide` |
| `test` | Add/modify tests | `test/account-invariant` |
| `chore` | Build, CI, dependencies | `chore/gradle-springdoc` |

**Rules:**
- Every word is written in `kebab-case`.
- Branch off `main`, and never commit/push directly to `main`.

### PR workflow

```
1. git checkout main && git pull origin main
2. git checkout -b <type>/<scope>-<short-description>
3. git commit -m "<type>(<scope>): <description>"
4. git push -u origin <branch-name>
5. gh pr create --base main --title "<type>(<scope>): <description>" --body "..."
```

### PR body

```markdown
## Summary
- Summarize the change in 1-3 lines

## Test plan
- [ ] ./harness.sh <projectRoot> passes
- [ ] Domain/Application unit tests pass
- [ ] E2E tests pass (Testcontainers)
```

### Merge strategy

- **Squash and merge** is the default. The remote branch is automatically deleted after merging.

---

## 14. Testing patterns

**See [architecture/testing.md](architecture/testing.md) for detailed rationale and code examples.** This section only covers a convention summary.

### 3 tiers — Domain / Application / E2E

| Tier | Framework | What's mocked |
|---|---|---|
| Domain | None (calls `Account.create(...)` directly) | None |
| Application | JUnit 5 + Mockito (`@ExtendWith(MockitoExtension.class)`) | Repository/Query **interfaces** |
| E2E | `@SpringBootTest` + Testcontainers | None (real containers) |

### Test file placement — the standard Gradle source set

```
src/test/java/com/example/accountservice/account/
  domain/AccountTest.java
  application/command/CreateAccountServiceTest.java
  interfaces/rest/AccountControllerE2ETest.java
```

Rather than placing a `.spec.ts` next to the source file as NestJS does, this mirrors the same package structure inside a `src/test` tree separate from `src/main`.

### Test method naming — a full descriptive sentence (this repository's established convention)

```java
// Correct — the already-adopted convention
@Test
void throws_exception_when_depositing_to_a_suspended_account() { ... }

@Test
void returns_201_and_account_info_when_creation_request_is_valid() { ... }
```

Java method names can use underscores as-is. Rather than forcing the root's `<action>_when_<condition>_then_<expected_result>` template exactly, this repository follows its own established convention of expressing test intent as a full descriptive sentence in snake_case. The method name itself reads clearly with no `@DisplayName` needed.

### Verifying exceptions — via `ErrorCode`, not a string

```java
// Correct
assertThatThrownBy(() -> account.withdraw(2000))
        .isInstanceOf(AccountException.class)
        .extracting(e -> ((AccountException) e).code())
        .isEqualTo(AccountException.ErrorCode.INSUFFICIENT_BALANCE);
```

```java
// Incorrect — the test breaks if the message wording changes
assertThatThrownBy(() -> account.withdraw(2000)).hasMessage("Insufficient balance.");
```

### E2E — Testcontainers only, no in-memory DB

```java
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountControllerE2ETest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
    }
}
```

Do not substitute an in-memory DB like H2 — this is to avoid false positives/negatives caused by SQL dialect differences from the production DB (Postgres).

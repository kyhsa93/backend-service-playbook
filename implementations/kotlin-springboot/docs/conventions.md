# Coding Conventions — Kotlin Spring Boot

> For the framework-agnostic principles (REST design, commit/branch conventions, etc), see [root conventions.md](../../../docs/conventions.md). This document focuses on Kotlin/Spring Boot implementation details.

## 1. File naming rules

The Kotlin/Java ecosystem doesn't follow the root's kebab-case filename rule. **filename = the top-level public class name (PascalCase)** is the standard — both `kotlinc`/IntelliJ assume this. The harness's `file-naming` check (`^[A-Z][A-Za-z0-9]*$`) enforces it.

| Kind | Location | Filename pattern | Example |
|------|------|------------|------|
| Aggregate Root | `domain/` | `<AggregateRoot>.kt` | `Order.kt` |
| Child Entity | `domain/` | `<Entity>.kt` | `OrderItem.kt` (if promoted to an Entity) |
| Value Object | `domain/` | `<ValueObject>.kt` | `Money.kt` |
| enum class | `domain/` | `<Concept>.kt` | `OrderStatus.kt` |
| Domain Event | `domain/` | `<PascalCase past tense>Event.kt` | `OrderCancelledEvent.kt` |
| Exception hierarchy | `domain/` | `<Domain>Exception.kt` (a sealed class + its subclasses in one file) | `OrderException.kt` |
| Error code | `domain/` | `<Domain>ErrorCode.kt` | `OrderErrorCode.kt` |
| Repository interface | `domain/` | `<Aggregate>Repository.kt` | `OrderRepository.kt` |
| Repository implementation | `infrastructure/persistence/` | `<Aggregate>RepositoryImpl.kt` | `OrderRepositoryImpl.kt` |
| Spring Data JPA Repository | `infrastructure/persistence/` | `<Entity>JpaRepository.kt` | `OrderJpaRepository.kt` |
| Command Service | `application/command/` | `<Verb><Noun>Service.kt` | `CreateOrderService.kt` |
| Command | `application/command/` | `<Verb><Noun>Command.kt` | `CreateOrderCommand.kt` |
| Query Service | `application/query/` | `<Verb><Noun>Service.kt` | `GetOrderService.kt` |
| Query interface (when split out for read optimization) | `application/query/` | `<Aggregate>Query.kt` | `OrderQuery.kt` |
| Result | `application/{command,query}/` | `<Verb><Noun>Result.kt` | `GetOrderResult.kt` |
| Adapter interface | `application/adapter/` | `<ExternalDomain>Adapter.kt` | `UserAdapter.kt` |
| Adapter implementation | `infrastructure/` | `<ExternalDomain>AdapterImpl.kt` | `UserAdapterImpl.kt` |
| Technical-infrastructure Service interface | `application/service/` | `<Concern>Service.kt` | `CryptoService.kt` |
| Technical-infrastructure Service implementation | `infrastructure/` | `<Concern>ServiceImpl.kt` | `CryptoServiceImpl.kt` |
| Event Listener/Handler | `application/event/` | `<Domain><meaning>Handler.kt` or `<Domain>EventHandler.kt` | `OrderNotificationHandler.kt` |
| HTTP Controller | `interfaces/rest/` | `<Domain>Controller.kt` | `OrderController.kt` |
| The Request/Response DTO collection | `interfaces/rest/` | `Schemas.kt` or `<Domain>Schemas.kt` | `Schemas.kt` |
| `@ConfigurationProperties` | `config/` | `<Concern>Properties.kt` | `DatabaseProperties.kt` |
| `@Configuration` (a Bean factory) | `infrastructure/` | `<Concern>Config.kt` | `SesConfig.kt` |
| Scheduler | `infrastructure/` or the shared `outbox/`/`scheduling/` | `<Concern>Poller.kt`/`<Concern>Scheduler.kt` | `OutboxPoller.kt` |

**Note**: don't confuse this repository's convention — PascalCase, not kebab-case, is the correct filename here — with the root (TypeScript)/harness of other language implementations. See the "File naming rules — Kotlin/Java convention" section of [directory-structure.md](architecture/directory-structure.md).

**Note**: `Schemas.kt` is the convention for when a domain has two or more Request/Response DTOs. A module with exactly one DTO conflicts with ktlint's `standard:filename` rule (if a file has only one top-level declaration, the filename must match that declaration), so that DTO class's name is used as the filename directly (e.g. `IssueCardRequest.kt`). Once a second DTO is added, they're merged into `Schemas.kt` (or `<Domain>Schemas.kt`).

The REST endpoint's URL path itself still uses kebab-case per the root principle (`/order-items`) — this is an HTTP path rule, not a filename rule. See section 8.

---

## 2. Class naming rules

- Aggregate Root: a domain noun, `Order`, `Account`
- Value Object: a domain concept, `Money`, `OrderItem`
- Domain Event: past tense + the `Event` suffix, `OrderCreatedEvent`, `OrderCancelledEvent`
- Exception hierarchy: `sealed class <Domain>Exception`, subclasses are `<PascalCase situation>Exception` — `OrderNotFoundException`, `OrderAlreadyCancelledException`
- Error code enum: `<Domain>ErrorCode`, values are `SCREAMING_SNAKE_CASE` — `ORDER_NOT_FOUND`
- Repository interface: `<Aggregate>Repository` (a Kotlin `interface`, not an `abstract class`)
- Repository implementation: `<Aggregate>RepositoryImpl`
- Command Service: `<Verb><Noun>Service` — `CreateOrderService`, `CancelOrderService` (one class per use case)
- Query Service: `<Verb><Noun>Service` — `GetOrderService`, `GetOrdersService`
- Query interface (when split out): `<Aggregate>Query`
- Command: `<Verb><Noun>Command` — `CancelOrderCommand`
- Result: `<Verb><Noun>Result` — `GetOrderResult`, `CreateOrderResult`
- Adapter interface: `<ExternalDomain>Adapter` — `UserAdapter`
- Adapter implementation: `<ExternalDomain>AdapterImpl`
- Technical-infrastructure Service interface: `<Concern>Service` — `CryptoService`, `NotificationService`, `StorageService`
- Technical-infrastructure Service implementation: `<Concern>ServiceImpl`
- Controller: `<Domain>Controller`
- `@ConfigurationProperties` data class: `<Concern>Properties` — `DatabaseProperties`, `JwtProperties`

Giving each use case its own Service class ([cqrs-pattern.md](architecture/cqrs-pattern.md)) fits this repository's convention better than grouping multiple use cases into one class, like Java's `OrderService.create()/cancel()/...` — thanks to Kotlin's one-line constructor-injection declaration, the cost of the class count growing is small.

---

## 3. enum / constant placement

Kotlin follows the root's (TypeScript's) rule of "split an enum/constant into a separate file, at the module root" as-is, though a single file isn't strictly required to hold only one top-level declaration — however, this repository's principle is to **split concepts with different layer characteristics (a domain-state enum, an exception hierarchy, an error code) into their own separate `.kt` files**.

```kotlin
// domain/OrderStatus.kt — a domain status value as an enum class, in its own file
enum class OrderStatus { PENDING, PAID, CANCELLED }
```

```kotlin
// domain/OrderConstants.kt — domain constants grouped as top-level const val or an object
const val MAX_ORDER_AMOUNT = 9_999_999L

object OrderPolicy {
    const val MAX_ITEMS_PER_ORDER = 100
}
```

Since Kotlin supports top-level function/constant declarations, a single file is itself a logical module, instead of scattering `export const` like TypeScript — wrapping things in an `object` is only used when you want to group related constants into a namespace.

An enum used in the Application layer (Command/Result/Query) is likewise defined in `domain/` and imported for use — as long as an enum represents domain state, it's never placed outside domain/.

---

## 4. Kotlin typing patterns

### Aggregate/Entity — `private set` + `protected constructor()` + a `companion object` factory

```kotlin
@Entity
@Table(name = "orders")
class Order protected constructor() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        private set

    @Column(nullable = false, unique = true)
    var orderId: String = ""
        private set

    @Enumerated(EnumType.STRING)
    var status: OrderStatus = OrderStatus.PENDING
        private set

    companion object {
        fun create(userId: String, items: List<OrderItem>): Order {
            if (items.isEmpty()) throw OrderItemsEmptyException()
            return Order().apply {
                this.orderId = generateId()
                this.userId = userId
                this.items = items
                this.status = OrderStatus.PENDING
            }
        }
    }
}
```

See [tactical-ddd.md](architecture/tactical-ddd.md) for the detailed rationale. **Every property is `private set`**, with no way to assign to it directly from outside — a state change only happens through a domain method (`cancel()`, etc).

### Value Object — `data class` (no need to hand-implement `equals()`)

```kotlin
@Embeddable
data class Money(val amount: Long, val currency: String) {
    init {
        if (amount < 0) throw InvalidMoneyAmountException()
    }
    fun add(other: Money): Money = Money(amount + other.amount, currency)
}
```

The `data class` keyword auto-generates the "attribute-based equality comparison (`equals()`)" the root (TypeScript/Java) requires — no separate implementation needed. Invariants are validated immediately in the `init` block.

### Null-safety — `T?` instead of `Optional<T>`/`T | undefined`

```kotlin
// correct — a nullable type + the Elvis operator
fun findByOrderId(orderId: String): Order? = jpaRepository.findByOrderId(orderId)

val order = orderRepository.findByOrderId(orderId) ?: throw OrderNotFoundException(orderId)
// from here on, order is smart-cast to the non-null Order type
```

Kotlin doesn't need the root's (TypeScript's) two-track representation, like "a nullable DB field is `string | null`, an optional parameter is `?`" — **every "may have no value" case is unified into a single `T?`**. This one type expresses all three cases — "not found" (a Repository return), "an optional parameter" (a function argument's default value, `= null`), and "a nullable DB column" (an entity property) — with no need for Java's `Optional<T>` wrapping or TypeScript's `| undefined` distinction. Trying to use a nullable value as-is without `?:` simply doesn't compile, so a missing null check is blocked at the source.

```kotlin
// an optional parameter — defaults to null
fun getOrders(status: List<OrderStatus>? = null, page: Int = 0, take: Int = 20)

// a nullable DB column
@Column
var completedAt: LocalDateTime? = null
    private set
```

The Kotlin rule corresponding to "never use `any`" (the same principle as the root) is: **never use the `Any`/`Any?` type in a domain/Application layer's public signature** — unless there's an unavoidable case, like an internal collection holding Domain Events (`MutableList<Any>`), create a concrete type via `sealed interface`, as [domain-events.md](architecture/domain-events.md) recommends.

### sealed class/interface — an exhaustive `when`

```kotlin
sealed class OrderException(message: String, val code: OrderErrorCode, val httpStatus: HttpStatus) : RuntimeException(message)

class OrderNotFoundException(orderId: String) :
    OrderException("order not found: $orderId", OrderErrorCode.ORDER_NOT_FOUND, HttpStatus.NOT_FOUND)
```

Since only subclassing within the same file is allowed, the compiler knows every subtype, and catches a missed-handling case at compile time when a new exception is added to a `when (exception) { ... }` branch. See [error-handling.md](architecture/error-handling.md) for details.

### Time values — `LocalDateTime`, no timezone conversion needed

The root (TypeScript) specifies a manual UTC ↔ KST conversion rule, but Spring Boot solves this at the framework level via `spring.jackson.time-zone` / the DB connection's timezone setting — application code never calls a separate conversion function after `LocalDateTime.now()`. The principle of never changing the server/DB timezone setting itself is kept the same as the root.

### Complex types — nested `data class`

```kotlin
data class GetOrdersResult(val orders: List<OrderSummary>, val count: Long) {
    data class OrderSummary(val orderId: String, val status: String, val totalAmount: Long)
}
```

Instead of an intersection type like the root's `type OrderWithItems = Order & { items: OrderItem[] }`, Kotlin expresses the response schema hierarchically with a **nested data class** — it's self-contained in one file, with no separate file or static inner class + Lombok needed.

---

## 5. REST API endpoint design rules

The URL structure, HTTP methods/response codes, non-CRUD action expression, resource nesting, and kebab-case rules are the same as section 1 of [root conventions.md](../../../docs/conventions.md) — since these are language-agnostic principles, they aren't repeated here. This section covers only the Kotlin/Spring MVC implementation.

```kotlin
@RestController
@RequestMapping("/orders")
class OrderController(
    private val createOrderService: CreateOrderService,
    private val cancelOrderService: CancelOrderService,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createOrder(
        authentication: Authentication,
        @Valid @RequestBody request: CreateOrderRequest,
    ): CreateOrderResult =
        createOrderService.create(CreateOrderCommand(authentication.name, request.items))

    @PostMapping("/{orderId}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun cancelOrder(
        authentication: Authentication,
        @PathVariable orderId: String,
        @Valid @RequestBody request: CancelOrderRequest,
    ) {
        cancelOrderService.cancel(CancelOrderCommand(orderId, authentication.name, request.reason))
    }
}
```

- Use either `@ResponseStatus` (a class return) or `ResponseEntity<T>` (when the status code must be determined dynamically) — `@ResponseStatus` is more concise when only a single, static status code is ever returned.
- Pagination is expressed with `@RequestParam(defaultValue = "0") page`, `@RequestParam(defaultValue = "20") take` — see [api-response.md](architecture/api-response.md) for details.
- The list response's key name must be the plural of the domain object (`orders`) — `result`/`data`/`items` are forbidden.

---

## 6. Method naming and organization

### Controller methods

- Use verbs like `create`, `get`, `find`, `cancel`, `close` — since the Spring MVC annotation (`@PostMapping`, etc) determines the HTTP method/path, the method name itself is free, but the verb convention is kept for consistency with the root.
- **Never use `suspend fun`** — this repository is a Spring MVC (blocking) + Spring Data JPA (a blocking driver) combination, and has decided not to adopt coroutines (see the "Why coroutines aren't used" section of [scheduling.md](architecture/scheduling.md)). Don't introduce `suspend fun` in new code.
- Specify the return type explicitly (never leave a public API function to type inference) — `fun getOrder(...): GetOrderResult`.

### Service class organization order

1. Primary constructor parameters (Repository, other Services, etc — declared with `private val`, becoming a field at the same time)
2. `companion object` (if any — constants, static factories, etc)
3. Public business methods
4. Private helper methods

```kotlin
@Service
@Transactional
class CancelOrderService(
    private val orderRepository: OrderRepository,
) {
    fun cancel(command: CancelOrderCommand): Unit {
        val order = findOrderOrThrow(command.orderId)
        order.cancel(command.reason)
        orderRepository.saveOrder(order)
    }

    private fun findOrderOrThrow(orderId: String): Order =
        orderRepository.findOrders(OrderFindQuery(orderId = orderId, take = 1, page = 0))
            .first.firstOrNull() ?: throw OrderNotFoundException(orderId)
}
```

### About `open` — handled automatically by the compiler plugin

A Kotlin class is `final` by default, but the `kotlin("plugin.spring")` compiler plugin automatically makes a class annotated with a `@Component`-family annotation (`@Service`/`@Repository`/`@Configuration`) `open` — the `open` keyword is never written directly in the source (see [module-pattern.md](architecture/module-pattern.md)).

### A private environment-branching helper

```kotlin
private fun paymentApiUrl(): String =
    if (activeProfile == "prod") "https://payment.api.example.com" else "https://payment.dev.api.example.com"
```

Wherever possible, a value that needs environment branching should be moved into `@ConfigurationProperties` first ([config.md](architecture/config.md)) — a private helper method should only be used for lightweight logic that's awkward to move into configuration.

---

## 7. Import organization pattern

Since a Kotlin/JVM package is always a fully-qualified path, the root's (TypeScript's) "no relative paths, use the `@/` alias" rule doesn't even apply here — Kotlin has no concept of a relative import. Instead, follow the rules below.

- **Wildcard imports (`import com.example.orderservice.*`) are forbidden** — import each class individually. Enable "Use single name import" in IntelliJ's default settings (`Settings > Editor > Code Style > Kotlin > Imports`) to enforce this automatically.
- **Import order is alphabetical** — it's fine to split into groups in the order standard library (`kotlin.*`, `java.*`), third-party (`org.springframework.*`, `jakarta.*`, etc), then internal project imports, but it's also fine to just follow ktlint/IntelliJ's default sort (fully alphabetical, no group distinction) — this repository's principle is to **just trust the sorting-automation tool's (ktlint's) default** over manual group alignment (never manually align groups yourself).
- **Remove unused imports** — clean these up automatically with IntelliJ's "Optimize Imports" or ktlint's `no-unused-imports` rule.
- **Use an `as` alias only on a name conflict** — only use something like `import com.example.userservice.Order as UserOrder` when a same-named class exists in a different package (e.g. multiple BCs each having an `Order` class).

```kotlin
// correct
import jakarta.persistence.Entity
import jakarta.validation.constraints.NotBlank
import org.springframework.stereotype.Service
import java.time.LocalDateTime

class CreateOrderService(/* ... */)
```

```kotlin
// incorrect — a wildcard
import jakarta.persistence.*
```

---

## 8. API documentation pattern — springdoc-openapi

The equivalent of NestJS's `@ApiProperty`/`@ApiOperation` (`@nestjs/swagger`) is **springdoc-openapi**'s `@Schema`/`@Operation`.

```kotlin
@Operation(operationId = "createOrder", summary = "Create an order")
@ApiResponse(responseCode = "201", description = "Created")
@PostMapping
@ResponseStatus(HttpStatus.CREATED)
fun createOrder(@Valid @RequestBody request: CreateOrderRequest): CreateOrderResult
```

```kotlin
data class CreateOrderRequest(
    @field:Schema(description = "The list of order items", required = true)
    @field:Size(min = 1)
    val items: List<OrderItemRequest>,
)
```

### The `@field:` use-site target — a required combination with a Kotlin data class + Bean Validation

```kotlin
data class CreateAccountRequest(
    @field:NotBlank
    @field:Email
    val email: String,
)
```

Kotlin attaches a constructor parameter's annotation to the **parameter itself** by default, but Jakarta Bean Validation only inspects an annotation attached to the **field** — omitting the `@field:` use-site target makes validation silently not work with no compile error. This is the most common mistake in this combination, so check for it every time a new DTO is written (see [cross-cutting-concerns.md](architecture/cross-cutting-concerns.md)).

### A deprecated endpoint

```kotlin
@Operation(operationId = "createOrderLegacy", deprecated = true)
```

Just like the root, it's never deleted immediately — it's marked `deprecated = true` to allow a migration window.

---

## 9. Logger pattern

### `kotlin-logging` is recommended (for new code)

```kotlin
private val logger = KotlinLogging.logger {}

logger.info { "Order creation complete: orderId=$orderId" }   // lazy lambda evaluation
```

`LoggerFactory.getLogger(Foo::class.java)` (direct SLF4J usage) still works too, but new code should use `kotlin-logging` — it removes the `::class.java` boilerplate, and lazy lambda evaluation eliminates the cost of string concatenation when the log level is disabled. See [observability.md](architecture/observability.md) for a detailed comparison.

### Structured logs — snake_case fields

```kotlin
logger.atInfo()
    .addKeyValue("order_id", orderId)
    .addKeyValue("total_amount", totalAmount)
    .log("Order creation complete")
```

A Kotlin property name is camelCase (`orderId`), but a log field name follows monitoring-platform convention and uses **snake_case** (`order_id`) — the same principle as the root.

### Logging is forbidden in the Domain layer

A `domain/` class like `Order`, `Money`, etc never imports a logger — logging is only performed at the Application layer or above.

---

## 10. Comment style

- Write business-domain explanations as inline (`//`) comments.
- **Never use KDoc (`/** ... */`) unless it's a public, library-facing API** — matching the root's "no JSDoc" principle, application code uses only `//`-style comments.
- Use section comments to mark logical divisions in a long Service method.

```kotlin
fun cancel(command: CancelOrderCommand) {
    // Look up the order and verify it exists
    val order = findOrderOrThrow(command.orderId)

    // Validate the domain rule and transition state
    order.cancel(command.reason)

    // Persist (including the Outbox)
    orderRepository.saveOrder(order)
}
```

---

## 11. Commit message convention

Follows the Conventional Commits rules in section 2 of [root conventions.md](../../../docs/conventions.md) as-is — no per-language convention is invented. Use the Kotlin domain package name (`order`, `account`, etc) as the scope.

```
feat(order): add order cancellation
fix(order): fix soft-deleted transactions being included in the account balance query
refactor(order): unify Repository method names into the find/save/delete<Noun> pattern
test(order): add unit tests for Order invariants
```

---

## 12. Branch and PR conventions

Same as section 3 of [root conventions.md](../../../docs/conventions.md) — `<type>/<scope>-<description>`, every word in kebab-case, branched from `main`, direct pushes to `main` forbidden, Squash and merge.

---

## 13. Test patterns

See [testing.md](architecture/testing.md) for the detailed rationale and full examples. This section only summarizes the conventions.

### Framework — JUnit 5 + MockK + AssertJ + Testcontainers

```kotlin
// build.gradle.kts
testImplementation("io.mockk:mockk:1.13.13")
```

Use **MockK** instead of Mockito — it naturally meshes with Kotlin `final` classes (an `interface` Repository can be mocked with either library, but this repository standardizes on MockK for Kotlin-idiom consistency), and the `every {}`/`verify {}` DSL naturally meshes with `data class` Command/Result objects.

### The 3 test layers — Domain (plain) → Application (MockK) → E2E (Testcontainers)

```kotlin
// A Domain unit test — plain Kotlin, no framework
class OrderTest {
    @Test
    fun `creating an order with no items throws an exception`() {
        assertThrows<OrderItemsEmptyException> { Order.create("user-1", emptyList()) }
    }
}
```

```kotlin
// An Application unit test — replacing the Repository with MockK
class CancelOrderServiceTest {
    private val orderRepository = mockk<OrderRepository>(relaxed = true)
    private val service = CancelOrderService(orderRepository)

    @Test
    fun `cancelling a nonexistent order throws an exception`() {
        every { orderRepository.findOrders(any()) } returns (emptyList<Order>() to 0L)
        assertThrows<OrderNotFoundException> { service.cancel(CancelOrderCommand("no-such-id", "changed my mind")) }
    }
}
```

### Test file placement — Gradle's standard source-set layout

```
src/main/kotlin/.../order/domain/Order.kt
src/test/kotlin/.../order/domain/OrderTest.kt
src/test/kotlin/.../order/application/command/CancelOrderServiceTest.kt
src/test/kotlin/.../order/interfaces/rest/OrderControllerE2ETest.kt
```

Unlike NestJS/Jest's convention of placing a `.spec.ts` right next to the source file, Gradle/Maven's standard convention is to separate `src/main` and `src/test`, mirroring the same package structure inside each.

### Test naming — natural-language backtick function names

```kotlin
@Test
fun `cancelling an already-cancelled order throws an exception`() { /* ... */ }
```

Instead of the root's `{domain_action}_when_{condition}_then_{expected_result}` snake_case, Kotlin expresses test intent as a **complete natural-language sentence** wrapped in backticks — a convention this repository has already adopted ([testing.md](architecture/testing.md)), and new tests follow it too.

### E2E — Testcontainers, in-memory DBs forbidden

```kotlin
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderControllerE2ETest {
    companion object {
        @Container @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }
}
```

Instead of a substitute DB like H2 in-memory, spin up **a real Postgres via Testcontainers** — this avoids false positives/negatives caused by SQL dialect differences.

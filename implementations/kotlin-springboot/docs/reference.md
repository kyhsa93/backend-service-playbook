# Practical Implementation Template — Kotlin Spring Boot

A template that implements one complete domain (`Order`) as this architecture's **correct target state**. When adding a new domain, copy this template as a starting point (only changing the domain name).

> **Relationship with `examples/` (the Account domain)**: this template is not a direct copy of `examples/`'s current code. `examples/` hasn't yet reflected every "known gap" each `docs/architecture/*.md` document specifies (Aggregate ID hyphens, the Outbox not yet applied, the error response format, authentication not yet implemented, Repository method naming, etc). This template shows the **correct code** with all those gaps closed — each section ends with a link to the `architecture/*.md` document that justifies it.

---

## Directory structure

```
com.example.orderservice/
  OrderServiceApplication.kt          ← the @SpringBootApplication entry point (bootstrap.md)

  common/                             ← project-common infrastructure (shared-modules.md)
    GenerateId.kt                       ← a top-level function, Aggregate ID generation (aggregate-id.md)
    GlobalExceptionHandler.kt           ← @RestControllerAdvice (error-handling.md)
    CorrelationIdFilter.kt              ← Filter, MDC (cross-cutting-concerns.md)
    RequestLoggingInterceptor.kt        ← HandlerInterceptor
    WebConfig.kt                        ← @Configuration, registers the Interceptor/CORS

  config/                             ← the @ConfigurationProperties collection (config.md)
    DatabaseProperties.kt
    JwtProperties.kt

  auth/                               ← the shared authentication module (authentication.md)
    AuthService.kt                      ← token issuance
    JwtAuthenticationFilter.kt          ← Bearer token verification
    SecurityConfig.kt                   ← @Configuration, SecurityFilterChain

  outbox/                             ← Domain Event propagation infrastructure (domain-events.md)
    OutboxEvent.kt                      ← @Entity
    OutboxEventJpaRepository.kt
    OutboxWriter.kt                      ← writes an Outbox row inside Repository.save()'s transaction
    OutboxPoller.kt                      ← polls via @Scheduled → publishes to the message queue
    OutboxConsumer.kt                    ← message-queue long polling → routes to EventHandlerRegistry
    EventHandlerRegistry.kt              ← eventType → handler Map (constructor-injected)

  order/                              ← Bounded Context
    domain/
      Order.kt                          ← Aggregate Root (@Entity)
      OrderItem.kt                      ← Value Object (@Embeddable data class)
      OrderStatus.kt                    ← enum class
      OrderDomainEvent.kt                ← sealed interface
      OrderCreatedEvent.kt / OrderCancelledEvent.kt  ← data class (implements OrderDomainEvent)
      OrderException.kt                 ← the sealed class exception hierarchy
      OrderErrorCode.kt                 ← enum class
      OrderRepository.kt                ← the Repository interface (interface)
      PaymentRepository.kt              ← the Repository interface (interface)

    application/
      adapter/
        UserAdapter.kt                    ← an interface for calling an external BC (interface)
      service/
        CryptoService.kt                  ← a technical-infrastructure interface (interface)
      command/
        CreateOrderCommand.kt / CreateOrderResult.kt / CreateOrderService.kt
        CancelOrderCommand.kt / CancelOrderService.kt
        DeleteOrderCommand.kt / DeleteOrderService.kt
      query/
        OrderQuery.kt                     ← the read-only Query interface (interface)
        GetOrderResult.kt / GetOrderService.kt
        GetOrdersQuery.kt / GetOrdersResult.kt / GetOrdersService.kt
      event/
        OrderNotificationHandler.kt       ← application/event/, called by the queue Consumer

    infrastructure/
      persistence/
        OrderJpaRepository.kt             ← Spring Data's JpaRepository
        OrderRepositoryImpl.kt            ← @Repository, the OrderRepository implementation
        PaymentRepositoryImpl.kt
        OrderQueryImpl.kt                 ← @Component, the OrderQuery implementation (an EntityManager-based read-only query)
      UserAdapterImpl.kt                  ← @Component
      CryptoServiceImpl.kt                ← @Component
      OrderCleanupScheduler.kt            ← @Component, @Scheduled (optional — when a batch job is needed)

    interfaces/
      rest/
        OrderController.kt                ← @RestController
        Schemas.kt                        ← the collection of Request/Response data classes

  src/main/resources/
    application.yml
    db/migration/
      V1__create_orders.sql               ← Flyway (persistence.md)
```

This tree combines the actual Account package structure from [directory-structure.md](architecture/directory-structure.md) with the shared-package placement [shared-modules.md](architecture/shared-modules.md) proposes. Using `interfaces/` (plural) as the top-level interface-layer package name is this repository's convention — it differs from the root document's `interface/` (singular).

---

## The Domain layer

### Aggregate Root

```kotlin
// order/domain/Order.kt
package com.example.orderservice.order.domain

import com.example.orderservice.common.generateId
import jakarta.persistence.*
import java.time.LocalDateTime

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

    @Column(nullable = false)
    var userId: String = ""
        private set

    @ElementCollection
    @CollectionTable(name = "order_items", joinColumns = [JoinColumn(name = "order_id")])
    var items: List<OrderItem> = emptyList()
        private set

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: OrderStatus = OrderStatus.PENDING
        private set

    @Column(nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now()
        private set

    @Transient
    private val domainEvents: MutableList<OrderDomainEvent> = mutableListOf()

    companion object {
        fun create(userId: String, items: List<OrderItem>): Order {
            if (items.isEmpty()) throw OrderItemsEmptyException()
            return Order().apply {
                this.orderId = generateId()
                this.userId = userId
                this.items = items
                this.status = OrderStatus.PENDING
                this.createdAt = LocalDateTime.now()
                this.domainEvents += OrderCreatedEvent(this.orderId, this.userId, this.totalAmount(), this.createdAt)
            }
        }
    }

    fun cancel(reason: String) {
        if (status == OrderStatus.CANCELLED) throw OrderAlreadyCancelledException(orderId)
        if (status == OrderStatus.PAID) throw OrderPaidNotCancellableException(orderId)
        status = OrderStatus.CANCELLED
        domainEvents += OrderCancelledEvent(orderId, reason, LocalDateTime.now())
    }

    fun totalAmount(): Long = items.sumOf { it.price * it.quantity }

    fun pullDomainEvents(): List<OrderDomainEvent> = domainEvents.toList().also { domainEvents.clear() }
}
```

- `protected constructor()` + `companion object.create()`: the sole public creation path enforces the invariant "an order must have at least 1 item," and collects `OrderCreatedEvent` right at creation.
- Every property is `private set`: it can't be assigned directly from outside like `order.status = OrderStatus.CANCELLED`. A state change only happens through a domain method like `cancel()`.
- `id: Long?` (the JPA surrogate key) is separated from `orderId: String` (the domain identifier) — `id: Long` is never exposed anywhere in the Controller/Command/Result.

Rationale: [tactical-ddd.md](architecture/tactical-ddd.md), [aggregate-id.md](architecture/aggregate-id.md).

### Value Object

```kotlin
// order/domain/OrderItem.kt
package com.example.orderservice.order.domain

import jakarta.persistence.Embeddable

@Embeddable
data class OrderItem(
    val itemId: Long,
    val name: String,
    val price: Long,
    val quantity: Int,
) {
    init {
        if (price <= 0) throw InvalidPriceException()
        if (quantity <= 0) throw InvalidQuantityException()
    }
}
```

`data class` auto-generates `equals()`/`hashCode()`/`copy()` — there's no need for the attribute-based equality comparison you'd have to hand-write in the root (TypeScript/Java). The `init` block validates invariants at creation time. Rationale: [tactical-ddd.md](architecture/tactical-ddd.md).

### enum class

```kotlin
// order/domain/OrderStatus.kt
package com.example.orderservice.order.domain

enum class OrderStatus { PENDING, PAID, CANCELLED }
```

### Domain Event — layered via `sealed interface`

```kotlin
// order/domain/OrderDomainEvent.kt
package com.example.orderservice.order.domain

import java.time.LocalDateTime

sealed interface OrderDomainEvent {
    val orderId: String
}

data class OrderCreatedEvent(
    override val orderId: String,
    val userId: String,
    val totalAmount: Long,
    val createdAt: LocalDateTime,
) : OrderDomainEvent

data class OrderCancelledEvent(
    override val orderId: String,
    val reason: String,
    val cancelledAt: LocalDateTime,
) : OrderDomainEvent
```

Grouping event types under a `sealed interface` means the compiler checks the exhaustiveness of any subsequent `when (event) { ... }` branch in an EventHandler — a missed-handling case is caught at compile time when a new event type is added. Rationale: [domain-events.md](architecture/domain-events.md).

### The exception hierarchy — `sealed class` + error codes

```kotlin
// order/domain/OrderErrorCode.kt
package com.example.orderservice.order.domain

enum class OrderErrorCode {
    ORDER_NOT_FOUND,
    ORDER_ITEMS_EMPTY,
    INVALID_PRICE,
    INVALID_QUANTITY,
    ORDER_ALREADY_CANCELLED,
    ORDER_PAID_NOT_CANCELLABLE,
    PAYMENT_NOT_FOUND,
}
```

```kotlin
// order/domain/OrderException.kt
package com.example.orderservice.order.domain

import org.springframework.http.HttpStatus

sealed class OrderException(
    message: String,
    val code: OrderErrorCode,
    val httpStatus: HttpStatus,
) : RuntimeException(message)

class OrderNotFoundException(orderId: String) :
    OrderException("Order not found: $orderId", OrderErrorCode.ORDER_NOT_FOUND, HttpStatus.NOT_FOUND)

class OrderItemsEmptyException :
    OrderException("An order must have at least 1 item.", OrderErrorCode.ORDER_ITEMS_EMPTY, HttpStatus.BAD_REQUEST)

class InvalidPriceException :
    OrderException("The item price must be greater than 0.", OrderErrorCode.INVALID_PRICE, HttpStatus.BAD_REQUEST)

class InvalidQuantityException :
    OrderException("The quantity must be greater than 0.", OrderErrorCode.INVALID_QUANTITY, HttpStatus.BAD_REQUEST)

class OrderAlreadyCancelledException(orderId: String) :
    OrderException("This order is already cancelled: $orderId", OrderErrorCode.ORDER_ALREADY_CANCELLED, HttpStatus.CONFLICT)

class OrderPaidNotCancellableException(orderId: String) :
    OrderException("A paid order cannot be cancelled: $orderId", OrderErrorCode.ORDER_PAID_NOT_CANCELLABLE, HttpStatus.CONFLICT)

class PaymentNotFoundException(orderId: String) :
    OrderException("Payment info not found: $orderId", OrderErrorCode.PAYMENT_NOT_FOUND, HttpStatus.NOT_FOUND)
```

Since `sealed class` only allows subclassing within the same file, `GlobalExceptionHandler` covers every subtype just by catching `OrderException` alone — no handler changes are needed when adding a new exception. Rationale: [error-handling.md](architecture/error-handling.md).

### The Repository interface — a Kotlin `interface`

```kotlin
// order/domain/OrderRepository.kt
package com.example.orderservice.order.domain

interface OrderRepository {
    fun findOrders(query: OrderFindQuery): Pair<List<Order>, Long>
    fun saveOrder(order: Order)
    fun deleteOrder(orderId: String)
}

data class OrderFindQuery(
    val page: Int,
    val take: Int,
    val orderId: String? = null,
    val userId: String? = null,
    val status: List<OrderStatus>? = null,
)
```

```kotlin
// order/domain/PaymentRepository.kt
package com.example.orderservice.order.domain

interface PaymentRepository {
    fun findPaymentMethods(query: PaymentFindQuery): Pair<List<PaymentMethod>, Long>
    fun deletePaymentMethods(orderId: String)
}

data class PaymentFindQuery(val page: Int, val take: Int, val orderId: String? = null)
```

It's an `interface`, not an `abstract class` — Spring finds this interface's sole `@Repository` implementation on the classpath and auto-injects it, so there's no need for the `abstract class` workaround NestJS requires. Rationale: [repository-pattern.md](architecture/repository-pattern.md).

---

## The Application layer

### Command Service

```kotlin
// order/application/command/CreateOrderCommand.kt
package com.example.orderservice.order.application.command

data class CreateOrderCommand(
    val userId: String,
    val items: List<OrderItemInput>,
) {
    data class OrderItemInput(val itemId: Long, val name: String, val price: Long, val quantity: Int)
}
```

```kotlin
// order/application/command/CreateOrderResult.kt
package com.example.orderservice.order.application.command

import io.swagger.v3.oas.annotations.media.Schema

data class CreateOrderResult(
    @field:Schema(description = "The newly created order's ID.")
    val orderId: String,
    @field:Schema(description = "The order's lifecycle status. Always `PENDING` right after creation.", example = "PENDING")
    val status: String,
    @field:Schema(description = "The order total, summed across every line item.")
    val totalAmount: Long,
)
```

```kotlin
// order/application/command/CreateOrderService.kt
package com.example.orderservice.order.application.command

import com.example.orderservice.order.domain.Order
import com.example.orderservice.order.domain.OrderItem
import com.example.orderservice.order.domain.OrderRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class CreateOrderService(
    private val orderRepository: OrderRepository,
) {
    fun create(command: CreateOrderCommand): CreateOrderResult {
        val order = Order.create(
            userId = command.userId,
            items = command.items.map { OrderItem(it.itemId, it.name, it.price, it.quantity) },
        )
        orderRepository.saveOrder(order)
        return CreateOrderResult(order.orderId, order.status.name, order.totalAmount())
    }
}
```

```kotlin
// order/application/command/CancelOrderCommand.kt
package com.example.orderservice.order.application.command

data class CancelOrderCommand(val orderId: String, val reason: String)
```

```kotlin
// order/application/command/CancelOrderService.kt
package com.example.orderservice.order.application.command

import com.example.orderservice.order.domain.OrderFindQuery
import com.example.orderservice.order.domain.OrderNotFoundException
import com.example.orderservice.order.domain.OrderRepository
import com.example.orderservice.order.domain.PaymentRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class CancelOrderService(
    private val orderRepository: OrderRepository,
    private val paymentRepository: PaymentRepository,
) {
    fun cancel(command: CancelOrderCommand) {
        val (orders, _) = orderRepository.findOrders(OrderFindQuery(page = 0, take = 1, orderId = command.orderId))
        val order = orders.firstOrNull() ?: throw OrderNotFoundException(command.orderId)

        // The business rule is validated inside the Aggregate
        order.cancel(command.reason)

        paymentRepository.deletePaymentMethods(order.orderId)
        orderRepository.saveOrder(order)   // save() saves the Aggregate + the Outbox together internally
    }
}
```

`findOrders(take = 1)` + `.firstOrNull()` is the Kotlin idiom corresponding to the root's `.then((r) => r.orders.pop())` pattern — a dedicated `findOne`/`findById` method is never created. Rationale: [repository-pattern.md](architecture/repository-pattern.md), [cqrs-pattern.md](architecture/cqrs-pattern.md).

### The Query interface — split out as read-only

```kotlin
// order/application/query/OrderQuery.kt
package com.example.orderservice.order.application.query

interface OrderQuery {
    fun getOrders(query: GetOrdersQuery): GetOrdersResult
    fun getOrder(orderId: String, userId: String): GetOrderResult
}
```

```kotlin
// order/application/query/GetOrdersQuery.kt
package com.example.orderservice.order.application.query

data class GetOrdersQuery(val userId: String, val status: List<String>? = null, val page: Int = 0, val take: Int = 20)
```

```kotlin
// order/application/query/GetOrdersResult.kt
package com.example.orderservice.order.application.query

import io.swagger.v3.oas.annotations.media.Schema

data class GetOrdersResult(
    @field:Schema(description = "The requester's orders, newest first.")
    val orders: List<OrderSummary>,
    @field:Schema(description = "The total number of orders for this requester (not just the current page).")
    val count: Long,
) {
    data class OrderSummary(
        @field:Schema(description = "The order's ID.")
        val orderId: String,
        @field:Schema(description = "The order's lifecycle status.", example = "PENDING")
        val status: String,
        @field:Schema(description = "The order total, summed across every line item.")
        val totalAmount: Long,
    )
}
```

```kotlin
// order/application/query/GetOrderResult.kt
package com.example.orderservice.order.application.query

import io.swagger.v3.oas.annotations.media.Schema

data class GetOrderResult(
    @field:Schema(description = "The order's ID.")
    val orderId: String,
    @field:Schema(description = "The order's lifecycle status.", example = "PENDING")
    val status: String,
    @field:Schema(description = "The order total, summed across every line item.")
    val totalAmount: Long,
    @field:Schema(description = "The number of distinct line items on this order.")
    val itemCount: Int,
)
```

```kotlin
// order/application/query/GetOrdersService.kt
package com.example.orderservice.order.application.query

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class GetOrdersService(private val orderQuery: OrderQuery) {
    fun getOrders(query: GetOrdersQuery): GetOrdersResult = orderQuery.getOrders(query)
}

@Service
@Transactional(readOnly = true)
class GetOrderService(private val orderQuery: OrderQuery) {
    fun getOrder(orderId: String, userId: String): GetOrderResult = orderQuery.getOrder(orderId, userId)
}
```

The Query Service depends on a separate `OrderQuery` interface, not `Repository` — this is to run read-optimized queries (an EntityManager/JPQL projection) without the cost of reconstituting the Aggregate. `@Transactional(readOnly = true)` optimizes read performance by skipping Hibernate's dirty checking. Rationale: [cqrs-pattern.md](architecture/cqrs-pattern.md), [layer-architecture.md](architecture/layer-architecture.md).

### Adapter — a cross-domain call

```kotlin
// order/application/adapter/UserAdapter.kt
package com.example.orderservice.order.application.adapter

interface UserAdapter {
    fun findUser(userId: String): UserSummary?
}

data class UserSummary(val userId: String, val displayName: String, val email: String)
```

`UserSummary?` (nullable) expresses "not found" — the caller can't compile without `?:`. Rationale: [cross-domain.md](architecture/cross-domain.md).

### A technical-infrastructure Service

```kotlin
// order/application/service/CryptoService.kt
package com.example.orderservice.order.application.service

interface CryptoService {
    fun encrypt(plainText: String): String
    fun decrypt(cipherText: String): String
}
```

The Application Service depends only on the `CryptoService` interface; only the implementation in `infrastructure/` knows the actual algorithm (AES/KMS, etc).

### Event Handler — called by the Outbox Consumer

```kotlin
// order/application/event/OrderNotificationHandler.kt
package com.example.orderservice.order.application.event

import com.example.orderservice.order.domain.OrderCancelledEvent
import com.example.orderservice.order.domain.OrderCreatedEvent
import com.example.orderservice.order.domain.OrderDomainEvent
import org.springframework.stereotype.Component

@Component
class OrderNotificationHandler {

    fun handle(event: OrderDomainEvent) = when (event) {
        is OrderCreatedEvent -> handleCreated(event)
        is OrderCancelledEvent -> handleCancelled(event)
        // a missed branch when a new event type is added is a compile error (exhaustive when)
    }

    private fun handleCreated(event: OrderCreatedEvent) { /* send a notification, etc */ }
    private fun handleCancelled(event: OrderCancelledEvent) { /* send a notification, etc */ }
}
```

This Handler is called not by a synchronous `@EventListener`, but by **`outbox/OutboxConsumer` routing a message it received from the message queue to `outbox/EventHandlerRegistry`** — an asynchronous path, separate from the Command Service's transaction, that assumes at-least-once delivery. Rationale: [domain-events.md](architecture/domain-events.md).

---

## The Infrastructure layer

### The Repository implementation — saved in the same transaction as the Outbox

```kotlin
// order/infrastructure/persistence/OrderRepositoryImpl.kt
package com.example.orderservice.order.infrastructure.persistence

import com.example.orderservice.order.domain.*
import com.example.orderservice.outbox.OutboxEvent
import com.example.orderservice.outbox.OutboxEventJpaRepository
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
class OrderRepositoryImpl(
    private val jpaRepository: OrderJpaRepository,
    private val outboxJpaRepository: OutboxEventJpaRepository,
    private val objectMapper: ObjectMapper,
    private val em: EntityManager,
) : OrderRepository {

    override fun findOrders(query: OrderFindQuery): Pair<List<Order>, Long> {
        val jpql = buildJpql(query, count = false)
        val countJpql = buildJpql(query, count = true)

        val orders = em.createQuery(jpql, Order::class.java)
            .apply { bindParams(this, query) }
            .setFirstResult(query.page * query.take)
            .setMaxResults(query.take)
            .resultList

        val count = em.createQuery(countJpql, Long::class.javaObjectType)
            .apply { bindParams(this, query) }
            .singleResult

        return orders to count
    }

    @Transactional
    override fun saveOrder(order: Order) {
        jpaRepository.save(order)
        // If there are domain events, save them together in the Outbox — same transaction, atomic commit/rollback
        val events = order.pullDomainEvents()
        if (events.isNotEmpty()) {
            outboxJpaRepository.saveAll(events.map { OutboxEvent.from(it, objectMapper) })
        }
    }

    @Transactional
    override fun deleteOrder(orderId: String) {
        val order = jpaRepository.findByOrderIdAndDeletedAtIsNull(orderId) ?: return
        order.markDeleted()
        jpaRepository.save(order)
    }

    private fun buildJpql(query: OrderFindQuery, count: Boolean): String {
        val select = if (count) "SELECT COUNT(o)" else "SELECT o"
        val sb = StringBuilder("$select FROM Order o WHERE o.deletedAt IS NULL")
        if (!query.orderId.isNullOrBlank()) sb.append(" AND o.orderId = :orderId")
        if (!query.userId.isNullOrBlank()) sb.append(" AND o.userId = :userId")
        if (!query.status.isNullOrEmpty()) sb.append(" AND o.status IN :status")
        if (!count) sb.append(" ORDER BY o.orderId DESC")
        return sb.toString()
    }

    private fun bindParams(q: jakarta.persistence.Query, query: OrderFindQuery) {
        if (!query.orderId.isNullOrBlank()) q.setParameter("orderId", query.orderId)
        if (!query.userId.isNullOrBlank()) q.setParameter("userId", query.userId)
        if (!query.status.isNullOrEmpty()) q.setParameter("status", query.status)
    }
}
```

`saveOrder()` ties saving the Aggregate and saving to the Outbox together into a single `@Transactional` — the Command Service no longer calls `ApplicationEventPublisher.publishEvent()`. `deleteOrder()` is a soft delete (`markDeleted()`) and never uses a hard delete like `manager.delete()`. Assembling dynamic JPQL that only adds a condition when a value is present is already this repository's established convention. Rationale: [domain-events.md](architecture/domain-events.md), [persistence.md](architecture/persistence.md), [repository-pattern.md](architecture/repository-pattern.md).

### The Query implementation — a read-only projection

```kotlin
// order/infrastructure/persistence/OrderQueryImpl.kt
package com.example.orderservice.order.infrastructure.persistence

import com.example.orderservice.order.application.query.*
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component

@Component
class OrderQueryImpl(private val em: EntityManager) : OrderQuery {

    override fun getOrders(query: GetOrdersQuery): GetOrdersResult {
        // A read-only DTO projection — never reconstitutes the full Aggregate
        val rows = em.createQuery(
            "SELECT o.orderId, o.status, o.items FROM Order o WHERE o.userId = :userId ORDER BY o.orderId DESC",
            Array<Any>::class.java,
        ).setParameter("userId", query.userId)
            .setFirstResult(query.page * query.take)
            .setMaxResults(query.take)
            .resultList

        val summaries = rows.map { /* map a row → OrderSummary */ GetOrdersResult.OrderSummary("", "", 0) }
        return GetOrdersResult(summaries, summaries.size.toLong())
    }

    override fun getOrder(orderId: String, userId: String): GetOrderResult {
        val order = em.createQuery("SELECT o FROM Order o WHERE o.orderId = :orderId AND o.userId = :userId", com.example.orderservice.order.domain.Order::class.java)
            .setParameter("orderId", orderId)
            .setParameter("userId", userId)
            .resultList.firstOrNull() ?: throw com.example.orderservice.order.domain.OrderNotFoundException(orderId)

        return GetOrderResult(order.orderId, order.status.name, order.totalAmount(), order.items.size)
    }
}
```

### The Adapter implementation

```kotlin
// order/infrastructure/UserAdapterImpl.kt
package com.example.orderservice.order.infrastructure

import com.example.orderservice.order.application.adapter.UserAdapter
import com.example.orderservice.order.application.adapter.UserSummary
import com.example.userservice.user.application.service.UserService
import org.springframework.stereotype.Component

@Component
class UserAdapterImpl(private val userService: UserService) : UserAdapter {
    override fun findUser(userId: String): UserSummary? =
        userService.findById(userId)?.let { UserSummary(it.id, it.name, it.email) }
}
```

---

## The Interfaces layer

### Controller

```kotlin
// order/interfaces/rest/OrderController.kt
package com.example.orderservice.order.interfaces.rest

import com.example.orderservice.common.ErrorResponse
import com.example.orderservice.order.application.command.*
import com.example.orderservice.order.application.query.*
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/orders")
class OrderController(
    private val createOrderService: CreateOrderService,
    private val cancelOrderService: CancelOrderService,
    private val getOrdersService: GetOrdersService,
    private val getOrderService: GetOrderService,
) {
    @GetMapping
    @Operation(summary = "List the requester's orders", description = "Returns the requester's orders, optionally filtered by status, paginated with `page`/`take`.")
    @ApiResponse(responseCode = "200", description = "The order list was found.")
    fun getOrders(
        authentication: Authentication,
        @RequestParam(required = false) status: List<String>?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") take: Int,
    ): GetOrdersResult = getOrdersService.getOrders(GetOrdersQuery(authentication.name, status, page, take))

    @GetMapping("/{orderId}")
    @Operation(summary = "Look up an order", description = "Returns the order only if it belongs to the authenticated requester.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "The order was found."),
        ApiResponse(
            responseCode = "404",
            description = "No order exists with the given `orderId` (`ORDER_NOT_FOUND`).",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    fun getOrder(authentication: Authentication, @PathVariable orderId: String): GetOrderResult =
        getOrderService.getOrder(orderId, authentication.name)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an order", description = "Creates a new order from the given line items.")
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "The order was created."),
        ApiResponse(
            responseCode = "400",
            description = "One of: the item list is empty (`ORDER_ITEMS_EMPTY`), a price/quantity is not positive " +
                "(`INVALID_PRICE`/`INVALID_QUANTITY`), or request validation failed (`VALIDATION_FAILED`).",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    fun createOrder(authentication: Authentication, @Valid @RequestBody request: CreateOrderRequest): CreateOrderResult =
        createOrderService.create(CreateOrderCommand(authentication.name, request.items))

    @PostMapping("/{orderId}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Cancel an order", description = "Cancels an order that has not yet been paid.")
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "The order was cancelled."),
        ApiResponse(
            responseCode = "404",
            description = "No order exists with the given `orderId` (`ORDER_NOT_FOUND`).",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "409",
            description = "One of: the order is already cancelled (`ORDER_ALREADY_CANCELLED`), or a paid order " +
                "cannot be cancelled (`ORDER_PAID_NOT_CANCELLABLE`).",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    fun cancelOrder(@PathVariable orderId: String, @Valid @RequestBody request: CancelOrderRequest) {
        cancelOrderService.cancel(CancelOrderCommand(orderId, request.reason))
    }
}
```

- The Controller only pulls the authenticated user ID (`authentication.name`, the JWT subject) out of `Authentication` to include in a Command/Query — it never trusts a client-sent header without verification. Rationale: [authentication.md](architecture/authentication.md).
- The Controller has no `@ExceptionHandler` for error conversion — the global `common/GlobalExceptionHandler.kt` (`@RestControllerAdvice`) handles the entire `OrderException` hierarchy at once.
- Each documented `@ApiResponse` status code is cross-checked against the exception's own `httpStatus` field (see the exception hierarchy above) — `OrderAlreadyCancelledException`/`OrderPaidNotCancellableException` carry `HttpStatus.CONFLICT`, so they're documented as `409`, not guessed as a generic `400`. See [api-response.md](architecture/api-response.md)'s "Machine-readable API documentation (OpenAPI)" section for the exact completeness bar (every operation needs summary+description; every non-2xx status must be documented), and `harness/README.md`'s `openapi-operation-documented` rule for how this is mechanically enforced.

### Request/Response DTOs

```kotlin
// order/interfaces/rest/Schemas.kt
package com.example.orderservice.order.interfaces.rest

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size

data class CreateOrderRequest(
    @field:Schema(description = "The order items", required = true)
    @field:Size(min = 1, message = "An order must have at least 1 item.")
    val items: List<OrderItemRequest>,
) {
    data class OrderItemRequest(
        @field:Schema(description = "The catalog item's ID.")
        val itemId: Long,
        @field:NotBlank
        @field:Schema(description = "The item's display name, snapshotted at order time.")
        val name: String,
        @field:Positive
        @field:Schema(description = "The unit price. Must be greater than 0.")
        val price: Long,
        @field:Positive
        @field:Schema(description = "The quantity ordered. Must be greater than 0.")
        val quantity: Int,
    )
}

data class CancelOrderRequest(
    @field:NotBlank
    @field:Schema(description = "The cancellation reason")
    val reason: String,
)
```

The `@field:` use-site target must always be attached — omitting it makes Bean Validation silently not work with no compile error. Rationale: [cross-cutting-concerns.md](architecture/cross-cutting-concerns.md).

### The global error handler (shared, located in `common/`)

```kotlin
// common/GlobalExceptionHandler.kt
package com.example.orderservice.common

import com.example.orderservice.order.domain.OrderException
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

data class ErrorResponse(val statusCode: Int, val code: String, val message: String, val error: String)

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(OrderException::class)
    fun handleOrderException(e: OrderException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(e.httpStatus).body(
            ErrorResponse(e.httpStatus.value(), e.code.name, e.message ?: "", e.httpStatus.reasonPhrase),
        )

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(e: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> =
        ResponseEntity.badRequest().body(
            ErrorResponse(
                400, "VALIDATION_FAILED",
                e.bindingResult.fieldErrors.joinToString { "${it.field}: ${it.defaultMessage}" },
                "Bad Request",
            ),
        )

    @ExceptionHandler(Exception::class)
    fun handleUnknown(e: Exception): ResponseEntity<ErrorResponse> =
        ResponseEntity.internalServerError().body(
            ErrorResponse(500, "INTERNAL_SERVER_ERROR", "An unexpected error occurred.", "Internal Server Error"),
        )
}
```

Even when a new domain (a BC other than `Order`) is added, you only need to add one `@ExceptionHandler` for that BC's `sealed class <Domain>Exception` to this handler — it's never repeated per Controller. Rationale: [error-handling.md](architecture/error-handling.md).

---

## Component scanning — there's no separate module-registration file

NestJS requires explicitly registering every class in `OrderModule`'s `providers`/`controllers` arrays. Kotlin/Spring Boot has no such registration file at all — `@SpringBootApplication` (`OrderServiceApplication.kt`) already targets the entire `com.example.orderservice` subtree for component scanning, so every `@Service`/`@Repository`/`@Component`/`@RestController`/`@Configuration` class created above **is automatically registered as a bean and automatically injected, as long as it's inside the package and has the correct annotation attached.**

| NestJS `OrderModule` | Kotlin/Spring Boot equivalent |
|---|---|
| `providers: [OrderCommandService, ...]` | `@Service`/`@Component` + component scanning (no registration file) |
| `{ provide: OrderRepository, useClass: OrderRepositoryImpl }` | `interface OrderRepository` + `@Repository class OrderRepositoryImpl : OrderRepository` (auto-bound) |
| `controllers: [OrderController]` | `@RestController` (component scanning is enough, no separate array) |
| `imports: [TypeOrmModule.forFeature([...])]` | Spring Data JPA's `interface OrderJpaRepository : JpaRepository<Order, Long>` — Spring Data auto-generates the proxy |
| `exports: [OrderCommandService]` | none — as long as it's under the component-scan root, any package's bean can be injected (controlling external exposure is replaced by team convention + the harness) |

When adding a new domain, there's no need to think about "where do I register this" — attaching the correct stereotype annotation in the correct package is all there is to it. Rationale: [module-pattern.md](architecture/module-pattern.md).

---

## Migrations

```sql
-- src/main/resources/db/migration/V1__create_orders.sql
CREATE TABLE orders (
    id BIGSERIAL PRIMARY KEY,
    order_id VARCHAR(32) NOT NULL UNIQUE,
    user_id VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    deleted_at TIMESTAMP
);

CREATE TABLE order_items (
    order_id BIGINT NOT NULL REFERENCES orders(id),
    item_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    price BIGINT NOT NULL,
    quantity INT NOT NULL
);
```

The production profile sets `spring.jpa.hibernate.ddl-auto: validate`, and schema changes are always reflected via a new Flyway migration file — `ddl-auto: update` is local-only. Rationale: [persistence.md](architecture/persistence.md).

---

## Related documents

- [checklist.md](checklist.md) — the self-review checklist to run after working from this template
- [conventions.md](conventions.md) — the full naming/typing/import/test conventions
- [architecture/](architecture/) — the full set of detailed design documents each section is justified by

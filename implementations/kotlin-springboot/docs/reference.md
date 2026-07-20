# 실전 구현 템플릿 — Kotlin Spring Boot

전체 도메인 하나(`Order`)를 이 아키텍처의 **올바른 목표 상태**로 구현한 템플릿이다. 새 도메인을 추가할 때 이 템플릿을 복사해서 시작한다(도메인명만 변경).

> **`examples/`(Account 도메인)와의 관계**: 이 템플릿은 `examples/`의 현재 코드를 그대로 옮긴 것이 아니다. `examples/`는 `docs/architecture/*.md` 각 문서가 명시하는 "알려진 갭"(Aggregate ID 하이픈, Outbox 미적용, 에러 응답 형식, 인증 미구현, Repository 메서드 네이밍 등)을 아직 반영하지 않은 상태다. 이 템플릿은 그 갭이 모두 해소된 **정답 코드**를 보여준다 — 각 절 끝에 근거가 되는 `architecture/*.md` 링크를 단다.

---

## 디렉토리 구조

```
com.example.orderservice/
  OrderServiceApplication.kt          ← @SpringBootApplication 진입점 (bootstrap.md)

  common/                             ← 프로젝트 공통 인프라 (shared-modules.md)
    GenerateId.kt                       ← 최상위 함수, Aggregate ID 생성 (aggregate-id.md)
    GlobalExceptionHandler.kt           ← @RestControllerAdvice (error-handling.md)
    CorrelationIdFilter.kt              ← Filter, MDC (cross-cutting-concerns.md)
    RequestLoggingInterceptor.kt        ← HandlerInterceptor
    WebConfig.kt                        ← @Configuration, Interceptor/CORS 등록

  config/                             ← @ConfigurationProperties 모음 (config.md)
    DatabaseProperties.kt
    JwtProperties.kt

  auth/                               ← 인증 공유 모듈 (authentication.md)
    AuthService.kt                      ← JWT 발급
    JwtAuthenticationFilter.kt          ← Bearer 토큰 검증
    SecurityConfig.kt                   ← @Configuration, SecurityFilterChain

  outbox/                             ← Domain Event 전파 인프라 (domain-events.md)
    OutboxEvent.kt                      ← @Entity
    OutboxEventJpaRepository.kt
    OutboxWriter.kt                      ← Repository.save() 트랜잭션 안에서 Outbox 행 적재
    OutboxPoller.kt                      ← @Scheduled 폴링 → 메시지 큐 발행
    OutboxConsumer.kt                    ← 메시지 큐 long polling → EventHandlerRegistry로 라우팅
    EventHandlerRegistry.kt              ← eventType → 핸들러 Map(생성자 주입)

  order/                              ← Bounded Context
    domain/
      Order.kt                          ← Aggregate Root (@Entity)
      OrderItem.kt                      ← Value Object (@Embeddable data class)
      OrderStatus.kt                    ← enum class
      OrderDomainEvent.kt                ← sealed interface
      OrderCreatedEvent.kt / OrderCancelledEvent.kt  ← data class (OrderDomainEvent 구현)
      OrderException.kt                 ← sealed class 예외 계층
      OrderErrorCode.kt                 ← enum class
      OrderRepository.kt                ← Repository 인터페이스 (interface)
      PaymentRepository.kt              ← Repository 인터페이스 (interface)

    application/
      adapter/
        UserAdapter.kt                    ← 외부 BC 호출 인터페이스 (interface)
      service/
        CryptoService.kt                  ← 기술 인프라 인터페이스 (interface)
      command/
        CreateOrderCommand.kt / CreateOrderResult.kt / CreateOrderService.kt
        CancelOrderCommand.kt / CancelOrderService.kt
        DeleteOrderCommand.kt / DeleteOrderService.kt
      query/
        OrderQuery.kt                     ← 읽기 전용 Query 인터페이스 (interface)
        GetOrderResult.kt / GetOrderService.kt
        GetOrdersQuery.kt / GetOrdersResult.kt / GetOrdersService.kt
      event/
        OrderNotificationHandler.kt       ← application/event/, 큐 Consumer가 호출

    infrastructure/
      persistence/
        OrderJpaRepository.kt             ← Spring Data JpaRepository
        OrderRepositoryImpl.kt            ← @Repository, OrderRepository 구현체
        PaymentRepositoryImpl.kt
        OrderQueryImpl.kt                 ← @Component, OrderQuery 구현체 (EntityManager 기반 읽기 전용 쿼리)
      UserAdapterImpl.kt                  ← @Component
      CryptoServiceImpl.kt                ← @Component
      OrderCleanupScheduler.kt            ← @Component, @Scheduled (선택 — 배치가 필요할 때)

    interfaces/
      rest/
        OrderController.kt                ← @RestController
        Schemas.kt                        ← Request/Response data class 모음

  src/main/resources/
    application.yml
    db/migration/
      V1__create_orders.sql               ← Flyway (persistence.md)
```

이 트리는 [directory-structure.md](architecture/directory-structure.md)의 실제 Account 패키지 구조와 [shared-modules.md](architecture/shared-modules.md)가 제안하는 공유 패키지 배치를 합친 것이다. `interfaces/`(복수형)를 최상위 interface 레이어 패키지명으로 쓰는 것이 이 저장소의 관례다 — root 문서의 `interface/`(단수)와 다르다.

---

## Domain 레이어

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

- `protected constructor()` + `companion object.create()`: 유일한 공개 생성 경로가 "주문 항목은 최소 1개 이상"이라는 불변식을 강제하고, 생성 즉시 `OrderCreatedEvent`를 수집한다.
- 모든 프로퍼티가 `private set`: 외부에서 `order.status = OrderStatus.CANCELLED`처럼 직접 대입할 수 없다. 상태 변경은 `cancel()` 같은 도메인 메서드로만 이루어진다.
- `id: Long?`(JPA surrogate key)와 `orderId: String`(도메인 식별자)이 분리되어 있다 — Controller/Command/Result 어디에도 `id: Long`을 노출하지 않는다.

근거: [tactical-ddd.md](architecture/tactical-ddd.md), [aggregate-id.md](architecture/aggregate-id.md).

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

`data class`가 `equals()`/`hashCode()`/`copy()`를 자동 생성한다 — root(TypeScript/Java)에서 수동으로 작성해야 했던 속성 기반 동등성 비교가 필요 없다. `init` 블록이 생성 시점에 불변식을 검증한다. 근거: [tactical-ddd.md](architecture/tactical-ddd.md).

### enum class

```kotlin
// order/domain/OrderStatus.kt
package com.example.orderservice.order.domain

enum class OrderStatus { PENDING, PAID, CANCELLED }
```

### Domain Event — `sealed interface`로 계층화

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

`sealed interface`로 이벤트 타입을 묶으면, 이후 EventHandler에서 `when (event) { ... }` 분기의 완전성(exhaustiveness)을 컴파일러가 검사한다 — 새 이벤트 타입 추가 시 처리 누락을 컴파일 타임에 잡는다. 근거: [domain-events.md](architecture/domain-events.md).

### 예외 계층 — `sealed class` + 에러 코드

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
    OrderException("주문을 찾을 수 없습니다: $orderId", OrderErrorCode.ORDER_NOT_FOUND, HttpStatus.NOT_FOUND)

class OrderItemsEmptyException :
    OrderException("주문 항목은 최소 1개 이상이어야 합니다.", OrderErrorCode.ORDER_ITEMS_EMPTY, HttpStatus.BAD_REQUEST)

class InvalidPriceException :
    OrderException("상품 가격은 0보다 커야 합니다.", OrderErrorCode.INVALID_PRICE, HttpStatus.BAD_REQUEST)

class InvalidQuantityException :
    OrderException("수량은 0보다 커야 합니다.", OrderErrorCode.INVALID_QUANTITY, HttpStatus.BAD_REQUEST)

class OrderAlreadyCancelledException(orderId: String) :
    OrderException("이미 취소된 주문입니다: $orderId", OrderErrorCode.ORDER_ALREADY_CANCELLED, HttpStatus.CONFLICT)

class OrderPaidNotCancellableException(orderId: String) :
    OrderException("결제 완료된 주문은 취소할 수 없습니다: $orderId", OrderErrorCode.ORDER_PAID_NOT_CANCELLABLE, HttpStatus.CONFLICT)

class PaymentNotFoundException(orderId: String) :
    OrderException("결제 정보를 찾을 수 없습니다: $orderId", OrderErrorCode.PAYMENT_NOT_FOUND, HttpStatus.NOT_FOUND)
```

`sealed class`는 같은 파일 내 상속만 허용하므로, `GlobalExceptionHandler`가 `OrderException` 하나만 잡아도 모든 하위 타입이 커버된다 — 새 예외 추가 시 핸들러 수정이 필요 없다. 근거: [error-handling.md](architecture/error-handling.md).

### Repository 인터페이스 — Kotlin `interface`

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

`abstract class`가 아니라 `interface`다 — Spring은 클래스패스에서 이 인터페이스의 유일한 `@Repository` 구현체를 찾아 자동 주입하므로, NestJS가 필요로 하는 `abstract class` 우회가 필요 없다. 근거: [repository-pattern.md](architecture/repository-pattern.md).

---

## Application 레이어

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

data class CreateOrderResult(val orderId: String, val status: String, val totalAmount: Long)
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

        // 비즈니스 규칙은 Aggregate 내부에서 검증
        order.cancel(command.reason)

        paymentRepository.deletePaymentMethods(order.orderId)
        orderRepository.saveOrder(order)   // save 내부에서 Aggregate + Outbox를 함께 저장
    }
}
```

`findOrders(take = 1)` + `.firstOrNull()`이 root의 `.then((r) => r.orders.pop())` 패턴에 대응하는 Kotlin 관용구다 — 전용 `findOne`/`findById` 메서드를 만들지 않는다. 근거: [repository-pattern.md](architecture/repository-pattern.md), [cqrs-pattern.md](architecture/cqrs-pattern.md).

### Query 인터페이스 — 읽기 전용 분리

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

data class GetOrdersResult(val orders: List<OrderSummary>, val count: Long) {
    data class OrderSummary(val orderId: String, val status: String, val totalAmount: Long)
}
```

```kotlin
// order/application/query/GetOrderResult.kt
package com.example.orderservice.order.application.query

data class GetOrderResult(val orderId: String, val status: String, val totalAmount: Long, val itemCount: Int)
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

Query Service는 `Repository`가 아니라 별도의 `OrderQuery` 인터페이스에 의존한다 — Aggregate 복원 비용 없이 읽기 최적화 쿼리(EntityManager/JPQL 프로젝션)를 실행하기 위해서다. `@Transactional(readOnly = true)`가 Hibernate의 dirty checking을 생략해 읽기 성능을 최적화한다. 근거: [cqrs-pattern.md](architecture/cqrs-pattern.md), [layer-architecture.md](architecture/layer-architecture.md).

### Adapter — 크로스 도메인 호출

```kotlin
// order/application/adapter/UserAdapter.kt
package com.example.orderservice.order.application.adapter

interface UserAdapter {
    fun findUser(userId: String): UserSummary?
}

data class UserSummary(val userId: String, val displayName: String, val email: String)
```

`UserSummary?`(nullable)가 "찾지 못함"을 표현한다 — 호출자는 `?:` 없이는 컴파일이 안 된다. 근거: [cross-domain.md](architecture/cross-domain.md).

### 기술 인프라 Service

```kotlin
// order/application/service/CryptoService.kt
package com.example.orderservice.order.application.service

interface CryptoService {
    fun encrypt(plainText: String): String
    fun decrypt(cipherText: String): String
}
```

Application Service는 `CryptoService` 인터페이스에만 의존하며, 실제 알고리즘(AES/KMS 등)은 `infrastructure/`의 구현체만 안다.

### Event Handler — Outbox Consumer가 호출

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
        // 새 이벤트 타입 추가 시 분기 누락하면 컴파일 에러 (exhaustive when)
    }

    private fun handleCreated(event: OrderCreatedEvent) { /* 알림 발송 등 */ }
    private fun handleCancelled(event: OrderCancelledEvent) { /* 알림 발송 등 */ }
}
```

이 Handler는 **동기 `@EventListener`가 아니라 `outbox/OutboxConsumer`가 메시지 큐에서 수신한 메시지를 `outbox/EventHandlerRegistry`로 라우팅**해 호출한다 — Command Service의 트랜잭션과 분리된, at-least-once 전달을 전제로 한 비동기 경로다. 근거: [domain-events.md](architecture/domain-events.md).

---

## Infrastructure 레이어

### Repository 구현체 — Outbox와 같은 트랜잭션으로 저장

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
        // 도메인 이벤트가 있으면 Outbox에 함께 저장 — 같은 트랜잭션, 원자적 커밋/롤백
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

`saveOrder()`가 Aggregate 저장과 Outbox 저장을 하나의 `@Transactional`로 묶는다 — Command Service는 더 이상 `ApplicationEventPublisher.publishEvent()`를 호출하지 않는다. `deleteOrder()`는 soft delete(`markDeleted()`)이며 `manager.delete()` 같은 hard delete를 쓰지 않는다. 값이 있을 때만 조건을 추가하는 동적 JPQL 조립은 이미 이 저장소의 확립된 관례다. 근거: [domain-events.md](architecture/domain-events.md), [persistence.md](architecture/persistence.md), [repository-pattern.md](architecture/repository-pattern.md).

### Query 구현체 — 읽기 전용 프로젝션

```kotlin
// order/infrastructure/persistence/OrderQueryImpl.kt
package com.example.orderservice.order.infrastructure.persistence

import com.example.orderservice.order.application.query.*
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component

@Component
class OrderQueryImpl(private val em: EntityManager) : OrderQuery {

    override fun getOrders(query: GetOrdersQuery): GetOrdersResult {
        // 읽기 전용 DTO 프로젝션 — Aggregate 전체를 복원하지 않는다
        val rows = em.createQuery(
            "SELECT o.orderId, o.status, o.items FROM Order o WHERE o.userId = :userId ORDER BY o.orderId DESC",
            Array<Any>::class.java,
        ).setParameter("userId", query.userId)
            .setFirstResult(query.page * query.take)
            .setMaxResults(query.take)
            .resultList

        val summaries = rows.map { /* row → OrderSummary 매핑 */ GetOrdersResult.OrderSummary("", "", 0) }
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

### Adapter 구현체

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

## Interfaces 레이어

### Controller

```kotlin
// order/interfaces/rest/OrderController.kt
package com.example.orderservice.order.interfaces.rest

import com.example.orderservice.order.application.command.*
import com.example.orderservice.order.application.query.*
import io.swagger.v3.oas.annotations.Operation
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
    @Operation(operationId = "getOrders")
    fun getOrders(
        authentication: Authentication,
        @RequestParam(required = false) status: List<String>?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") take: Int,
    ): GetOrdersResult = getOrdersService.getOrders(GetOrdersQuery(authentication.name, status, page, take))

    @GetMapping("/{orderId}")
    @Operation(operationId = "getOrder")
    fun getOrder(authentication: Authentication, @PathVariable orderId: String): GetOrderResult =
        getOrderService.getOrder(orderId, authentication.name)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createOrder")
    fun createOrder(authentication: Authentication, @Valid @RequestBody request: CreateOrderRequest): CreateOrderResult =
        createOrderService.create(CreateOrderCommand(authentication.name, request.items))

    @PostMapping("/{orderId}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "cancelOrder")
    fun cancelOrder(@PathVariable orderId: String, @Valid @RequestBody request: CancelOrderRequest) {
        cancelOrderService.cancel(CancelOrderCommand(orderId, request.reason))
    }
}
```

- Controller는 `Authentication`에서 인증된 사용자 ID(`authentication.name`, JWT subject)만 꺼내 Command/Query에 포함한다 — 클라이언트가 보낸 헤더를 검증 없이 신뢰하지 않는다. 근거: [authentication.md](architecture/authentication.md).
- 에러 변환용 `@ExceptionHandler`가 Controller에 없다 — 전역 `common/GlobalExceptionHandler.kt`(`@RestControllerAdvice`)가 `OrderException` 계층 전체를 한 번에 처리한다.

### Request/Response DTO

```kotlin
// order/interfaces/rest/Schemas.kt
package com.example.orderservice.order.interfaces.rest

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size

data class CreateOrderRequest(
    @field:Schema(description = "주문 항목", required = true)
    @field:Size(min = 1, message = "주문 항목은 최소 1개 이상이어야 합니다.")
    val items: List<OrderItemRequest>,
) {
    data class OrderItemRequest(
        val itemId: Long,
        @field:NotBlank val name: String,
        @field:Positive val price: Long,
        @field:Positive val quantity: Int,
    )
}

data class CancelOrderRequest(
    @field:NotBlank
    @field:Schema(description = "취소 사유")
    val reason: String,
)
```

`@field:` 사용 지정자를 반드시 붙인다 — 빠뜨리면 컴파일 에러 없이 Bean Validation이 조용히 동작하지 않는다. 근거: [cross-cutting-concerns.md](architecture/cross-cutting-concerns.md).

### 전역 에러 핸들러 (공유, `common/`에 위치)

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
            ErrorResponse(500, "INTERNAL_SERVER_ERROR", "예상치 못한 오류가 발생했습니다.", "Internal Server Error"),
        )
}
```

새 도메인(`Order` 외 다른 BC)을 추가해도 각 BC의 `sealed class <Domain>Exception`을 이 핸들러에 `@ExceptionHandler` 하나씩만 추가하면 된다 — Controller마다 반복하지 않는다. 근거: [error-handling.md](architecture/error-handling.md).

---

## 컴포넌트 스캔 — 별도 모듈 등록 파일이 없다

NestJS는 `OrderModule`의 `providers`/`controllers` 배열에 모든 클래스를 명시적으로 등록해야 한다. Kotlin/Spring Boot는 이런 등록 파일 자체가 없다 — `@SpringBootApplication`(`OrderServiceApplication.kt`)이 이미 `com.example.orderservice` 하위 전체를 컴포넌트 스캔 대상으로 삼으므로, 위에서 만든 모든 `@Service`/`@Repository`/`@Component`/`@RestController`/`@Configuration` 클래스가 **패키지 안에 있고 올바른 애노테이션이 붙어 있기만 하면 자동으로 빈으로 등록되고 자동으로 주입된다.**

| NestJS `OrderModule` | Kotlin/Spring Boot 대응 |
|---|---|
| `providers: [OrderCommandService, ...]` | `@Service`/`@Component` + 컴포넌트 스캔 (등록 파일 없음) |
| `{ provide: OrderRepository, useClass: OrderRepositoryImpl }` | `interface OrderRepository` + `@Repository class OrderRepositoryImpl : OrderRepository` (자동 바인딩) |
| `controllers: [OrderController]` | `@RestController` (컴포넌트 스캔으로 충분, 별도 배열 없음) |
| `imports: [TypeOrmModule.forFeature([...])]` | Spring Data JPA `interface OrderJpaRepository : JpaRepository<Order, Long>` — 프록시를 Spring Data가 자동 생성 |
| `exports: [OrderCommandService]` | 없음 — 컴포넌트 스캔 루트 하위라면 어떤 패키지의 빈이든 주입 가능 (외부 공개 제어는 팀 컨벤션 + harness로 대체) |

새 도메인을 추가할 때 "어디에 등록해야 하는가"를 고민할 필요가 없다 — 올바른 패키지에 올바른 스테레오타입 애노테이션을 붙이는 것이 전부다. 근거: [module-pattern.md](architecture/module-pattern.md).

---

## 마이그레이션

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

프로덕션 프로파일은 `spring.jpa.hibernate.ddl-auto: validate`로 설정하고, 스키마 변경은 항상 새 Flyway 마이그레이션 파일로 반영한다 — `ddl-auto: update`는 로컬 전용이다. 근거: [persistence.md](architecture/persistence.md).

---

## 관련 문서

- [checklist.md](checklist.md) — 이 템플릿으로 작업한 뒤 수행할 자기 검토 체크리스트
- [conventions.md](conventions.md) — 네이밍/타이핑/import/테스트 컨벤션 전체
- [architecture/](architecture/) — 각 절의 근거가 되는 상세 설계 문서 전체

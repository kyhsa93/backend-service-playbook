# Kotlin Spring Boot DDD 구현 가이드

이 문서는 [backend-service-playbook](../../../docs/architecture/)의 설계 원칙을 Kotlin Spring Boot로 구현할 때의 언어별 상세를 담는다.
Java Spring Boot와 구조는 동일하며, Kotlin 관용 표현으로 대체되는 부분에 집중한다.

---

## 패키지 구조

```
com.example.orderservice/
  order/
    domain/
      Order.kt               ← Aggregate Root (@Entity + open class)
      OrderItem.kt           ← Value Object (@Embeddable + data class)
      OrderStatus.kt         ← enum class
      OrderCancelledEvent.kt ← Domain Event (data class)
      OrderRepository.kt     ← Repository 인터페이스 (interface)
    application/
      command/
        CreateOrderService.kt
        CancelOrderService.kt
        CreateOrderCommand.kt  ← data class
        CancelOrderCommand.kt  ← data class
      query/
        GetOrderService.kt
        GetOrdersService.kt
        GetOrderResult.kt      ← data class
        GetOrdersResult.kt     ← data class
    infrastructure/
      persistence/
        OrderJpaRepository.kt  ← JpaRepository 확장
        OrderRepositoryImpl.kt ← @Repository 구현체
    interfaces/
      rest/
        OrderController.kt     ← @RestController
        CreateOrderRequest.kt  ← data class
        CancelOrderRequest.kt  ← data class
        ErrorResponse.kt       ← data class
```

---

## Java와 다른 Kotlin 관용 표현

### data class — Lombok 불필요

```kotlin
// Java record 대체
data class CreateOrderCommand(val userId: String, val items: List<ItemInput>) {
    data class ItemInput(val itemId: Int, val name: String, val price: Int, val quantity: Int)
}
```

### Nullable 타입 — Optional 불필요

```kotlin
// Java: Optional<Order>  →  Kotlin: Order?
fun findById(orderId: String): Order?
```

### Constructor injection — @Autowired 불필요

```kotlin
@Service
class CreateOrderService(
    private val orderRepository: OrderRepository,
) {
    // 주입은 primary constructor로 자동 처리
}
```

### sealed class — 에러 타입 계층

```kotlin
sealed class OrderException(message: String) : RuntimeException(message)
class OrderNotFoundException(orderId: String) : OrderException("order not found: $orderId")
class OrderAlreadyCancelledException : OrderException("order already cancelled")
class OrderPaidNotCancellableException : OrderException("paid order cannot be cancelled")
```

### open class — Spring AOP 프록시를 위해 필요

Spring은 AOP(트랜잭션 등)를 위해 클래스를 상속한다. Kotlin 클래스는 기본 `final`이므로 `@Service`, `@Repository` 클래스는 `open`으로 선언하거나 `kotlin-spring` Gradle 플러그인을 사용한다.

```kotlin
// 방법 1: 수동으로 open
@Service
open class CreateOrderService(...)

// 방법 2: build.gradle.kts에 플러그인 추가 (권장)
plugins {
    kotlin("plugin.spring") version "..."   // @Component 계열 자동 open
    kotlin("plugin.jpa") version "..."     // @Entity, @Embeddable 자동 open
}
```

---

## Repository 패턴

```kotlin
// domain/OrderRepository.kt — Spring 무의존
interface OrderRepository {
    fun findById(orderId: String): Order?
    fun findAll(query: OrderFindQuery): List<Order>
    fun countAll(query: OrderFindQuery): Long
    fun save(order: Order)
    fun delete(orderId: String)
}
```

```kotlin
// infrastructure/persistence/OrderRepositoryImpl.kt
@Repository
class OrderRepositoryImpl(
    private val jpaRepository: OrderJpaRepository,
    private val em: EntityManager,
) : OrderRepository {

    override fun findById(orderId: String): Order? =
        jpaRepository.findByOrderIdAndDeletedAtIsNull(orderId)

    override fun save(order: Order) = jpaRepository.save(order).let {}
}
```

---

## CQRS 패턴

```kotlin
// application/command/CreateOrderService.kt
@Service
@Transactional
class CreateOrderService(private val orderRepository: OrderRepository) {

    fun create(command: CreateOrderCommand) {
        val items = command.items.map { OrderItem(it.itemId, it.name, it.price, it.quantity) }
        val order = Order.create(command.userId, items)
        orderRepository.save(order)
    }
}

// application/query/GetOrderService.kt
@Service
@Transactional(readOnly = true)
class GetOrderService(private val orderRepository: OrderRepository) {

    fun getOrder(orderId: String): GetOrderResult {
        val order = orderRepository.findById(orderId)
            ?: throw OrderNotFoundException(orderId)
        return GetOrderResult(order.orderId, order.status.name, order.totalAmount())
    }
}
```

---

## Aggregate Root

```kotlin
// domain/Order.kt
@Entity
@Table(name = "orders")
class Order protected constructor() {

    @Column(nullable = false, unique = true)
    var orderId: String = ""
        private set

    @Enumerated(EnumType.STRING)
    var status: OrderStatus = OrderStatus.PENDING
        private set

    @ElementCollection
    @CollectionTable(name = "order_items", joinColumns = [JoinColumn(name = "order_id", referencedColumnName = "order_id")])
    var items: MutableList<OrderItem> = mutableListOf()
        private set

    @Column
    var deletedAt: LocalDateTime? = null
        private set

    @Transient
    private val domainEvents: MutableList<Any> = mutableListOf()

    companion object {
        fun create(userId: String, items: List<OrderItem>): Order {
            require(items.isNotEmpty()) { "주문 항목은 최소 1개 이상이어야 합니다." }
            return Order().apply {
                this.orderId = UUID.randomUUID().toString()
                this.userId = userId
                this.items = items.toMutableList()
            }
        }
    }

    fun cancel(reason: String) {
        check(status != OrderStatus.CANCELLED) { "이미 취소된 주문입니다." }
        check(status != OrderStatus.PAID) { "결제 완료된 주문은 취소할 수 없습니다." }
        status = OrderStatus.CANCELLED
        domainEvents += OrderCancelledEvent(orderId, reason, LocalDateTime.now())
    }

    fun totalAmount(): Int = items.sumOf { it.price * it.quantity }

    fun pullDomainEvents(): List<Any> = domainEvents.toList().also { domainEvents.clear() }
}
```

---

## 에러 처리

```kotlin
// interfaces/rest/OrderController.kt
@ExceptionHandler(OrderNotFoundException::class)
fun handleNotFound(e: OrderNotFoundException): ResponseEntity<ErrorResponse> =
    ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse(e.message ?: ""))

@ExceptionHandler(IllegalStateException::class)
fun handleBadRequest(e: IllegalStateException): ResponseEntity<ErrorResponse> =
    ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse(e.message ?: ""))
```

---

## Soft Delete

```kotlin
@Column
var deletedAt: LocalDateTime? = null
    private set

fun softDelete() {
    deletedAt = LocalDateTime.now()
}
```

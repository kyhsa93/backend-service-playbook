# Spring Boot DDD 구현 가이드

이 문서는 [backend-service-playbook](../../../docs/architecture/)의 설계 원칙을 Spring Boot(Java)로 구현할 때의 언어별 상세를 담는다.

---

## 패키지 구조

```
com.example.orderservice/
  order/
    domain/
      Order.java               ← Aggregate Root (@Entity)
      OrderItem.java           ← Value Object (@Embeddable)
      OrderStatus.java         ← Enum
      OrderCancelledEvent.java ← Domain Event
      OrderRepository.java     ← Repository 인터페이스 (Java interface)
    application/
      command/
        CreateOrderService.java
        CancelOrderService.java
        CreateOrderCommand.java
        CancelOrderCommand.java
      query/
        GetOrderService.java
        GetOrdersService.java
        GetOrderResult.java
        GetOrdersResult.java
    infrastructure/
      persistence/
        OrderJpaRepository.java  ← JpaRepository 확장
        OrderRepositoryImpl.java ← OrderRepository 구현체 (@Repository)
        OrderItemEntity.java
    interfaces/
      rest/
        OrderController.java     ← @RestController
        CreateOrderRequest.java
        CancelOrderRequest.java
        GetOrderResponse.java
```

> `domain/`에는 Spring 어노테이션을 사용하지 않는다 (`@Service`, `@Component`, `@Repository` 금지).

---

## 파일명·패키지 네이밍

| 대상 | 규칙 | 예시 |
|------|------|------|
| 파일명·클래스명 | `PascalCase` | `OrderRepository.java` |
| 패키지명 | 소문자 | `com.example.orderservice.order.domain` |
| 상수 | `UPPER_SNAKE_CASE` | `MAX_ITEMS` |
| 메서드·필드 | `camelCase` | `totalAmount`, `cancel()` |

---

## Repository 패턴

TypeScript의 `abstract class` 대신 Java `interface`를 사용한다.

```java
// domain/OrderRepository.java — Spring 무의존
public interface OrderRepository {
    Optional<Order> findById(String orderId);
    List<Order> findAll(OrderFindQuery query);
    long countAll(OrderFindQuery query);
    void save(Order order);
    void delete(String orderId);
}
```

구현체는 `infrastructure/persistence/`에 `@Repository`로 등록한다.

```java
// infrastructure/persistence/OrderRepositoryImpl.java
@Repository
@RequiredArgsConstructor
public class OrderRepositoryImpl implements OrderRepository {

    private final OrderJpaRepository jpaRepository;

    @Override
    public Optional<Order> findById(String orderId) {
        return jpaRepository.findByOrderIdAndDeletedAtIsNull(orderId)
                .map(this::toDomain);
    }

    private Order toDomain(OrderEntity entity) { ... }
}
```

```java
// infrastructure/persistence/OrderJpaRepository.java
public interface OrderJpaRepository extends JpaRepository<OrderEntity, Long> {
    Optional<OrderEntity> findByOrderIdAndDeletedAtIsNull(String orderId);
}
```

---

## Aggregate Root

```java
// domain/Order.java — @Entity + 비즈니스 로직
@Entity
@Table(name = "orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String orderId;

    @Column(nullable = false)
    private String userId;

    @Enumerated(EnumType.STRING)
    private OrderStatus status;

    @ElementCollection
    @CollectionTable(name = "order_items", joinColumns = @JoinColumn(name = "order_id"))
    private List<OrderItem> items;

    @Column
    private LocalDateTime deletedAt;

    @Transient
    private final List<Object> domainEvents = new ArrayList<>();

    public static Order create(String userId, List<OrderItem> items) {
        if (items.isEmpty()) throw new IllegalArgumentException("주문 항목은 최소 1개 이상이어야 합니다.");
        Order order = new Order();
        order.orderId = UUID.randomUUID().toString();
        order.userId = userId;
        order.items = new ArrayList<>(items);
        order.status = OrderStatus.PENDING;
        return order;
    }

    public void cancel(String reason) {
        if (this.status == OrderStatus.CANCELLED) throw new IllegalStateException("이미 취소된 주문입니다.");
        if (this.status == OrderStatus.PAID) throw new IllegalStateException("결제 완료된 주문은 취소할 수 없습니다.");
        this.status = OrderStatus.CANCELLED;
        this.domainEvents.add(new OrderCancelledEvent(this.orderId, reason, LocalDateTime.now()));
    }

    public int totalAmount() {
        return items.stream().mapToInt(i -> i.price() * i.quantity()).sum();
    }

    public List<Object> pullDomainEvents() {
        List<Object> events = new ArrayList<>(this.domainEvents);
        this.domainEvents.clear();
        return events;
    }
}
```

---

## CQRS 패턴

`@nestjs/cqrs` 대신 Command/Query Service 클래스를 직접 분리한다.

```java
// application/command/CreateOrderService.java
@Service
@RequiredArgsConstructor
@Transactional
public class CreateOrderService {

    private final OrderRepository orderRepository;

    public void create(CreateOrderCommand command) {
        List<OrderItem> items = command.items().stream()
                .map(i -> new OrderItem(i.itemId(), i.name(), i.price(), i.quantity()))
                .toList();
        Order order = Order.create(command.userId(), items);
        orderRepository.save(order);
    }
}

// application/command/CancelOrderService.java
@Service
@RequiredArgsConstructor
@Transactional
public class CancelOrderService {

    private final OrderRepository orderRepository;
    private final ApplicationEventPublisher eventPublisher;

    public void cancel(CancelOrderCommand command) {
        Order order = orderRepository.findById(command.orderId())
                .orElseThrow(() -> new EntityNotFoundException("주문을 찾을 수 없습니다."));
        order.cancel(command.reason());
        orderRepository.save(order);
        order.pullDomainEvents().forEach(eventPublisher::publishEvent);
    }
}
```

```java
// application/query/GetOrderService.java
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GetOrderService {

    private final OrderRepository orderRepository;

    public GetOrderResult getOrder(String orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new EntityNotFoundException("주문을 찾을 수 없습니다."));
        return new GetOrderResult(order.getOrderId(), order.getStatus().name(), order.totalAmount());
    }
}
```

---

## 에러 처리

도메인 예외는 `domain/` 패키지에 정의하고, Controller에서 `@ExceptionHandler`로 HTTP 상태 코드를 매핑한다.

```java
// interfaces/rest/OrderController.java
@ExceptionHandler(EntityNotFoundException.class)
public ResponseEntity<ErrorResponse> handleNotFound(EntityNotFoundException e) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(new ErrorResponse(e.getMessage()));
}

@ExceptionHandler(IllegalStateException.class)
public ResponseEntity<ErrorResponse> handleBadRequest(IllegalStateException e) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(new ErrorResponse(e.getMessage()));
}
```

---

## Soft Delete

`deletedAt` 컬럼으로 관리하며, `@SQLRestriction`(Hibernate 6+) 또는 쿼리 조건으로 필터링한다.

```java
@Entity
@SQLRestriction("deleted_at IS NULL")
public class OrderEntity {
    @Column
    private LocalDateTime deletedAt;
}
```

삭제 시 hard delete 대신 `deletedAt` 을 현재 시각으로 설정한다.

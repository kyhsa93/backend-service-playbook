# 코딩 컨벤션 — Kotlin Spring Boot

> 프레임워크 무관 원칙(REST 설계, 커밋/브랜치 컨벤션 등)은 [루트 conventions.md](../../../docs/conventions.md) 참조. 이 문서는 Kotlin/Spring Boot 구현 상세에 집중한다.

## 1. 파일 네이밍 규칙

Kotlin/Java 생태계는 root의 kebab-case 파일명 규칙을 따르지 않는다. **파일명 = 최상위 public 클래스명(PascalCase)**이 표준이다 — `kotlinc`/IntelliJ 모두 이를 전제로 한다. harness의 `file-naming` 검사(`^[A-Z][A-Za-z0-9]*$`)가 이를 강제한다.

| 종류 | 위치 | 파일명 패턴 | 예시 |
|------|------|------------|------|
| Aggregate Root | `domain/` | `<AggregateRoot>.kt` | `Order.kt` |
| 하위 Entity | `domain/` | `<Entity>.kt` | `OrderItem.kt` (Entity로 승격한 경우) |
| Value Object | `domain/` | `<ValueObject>.kt` | `Money.kt` |
| enum class | `domain/` | `<Concept>.kt` | `OrderStatus.kt` |
| Domain Event | `domain/` | `<PascalCase 과거형>Event.kt` | `OrderCancelledEvent.kt` |
| 예외 계층 | `domain/` | `<Domain>Exception.kt` (sealed class + 하위 클래스 한 파일) | `OrderException.kt` |
| 에러 코드 | `domain/` | `<Domain>ErrorCode.kt` | `OrderErrorCode.kt` |
| Repository 인터페이스 | `domain/` | `<Aggregate>Repository.kt` | `OrderRepository.kt` |
| Repository 구현체 | `infrastructure/persistence/` | `<Aggregate>RepositoryImpl.kt` | `OrderRepositoryImpl.kt` |
| Spring Data JPA Repository | `infrastructure/persistence/` | `<Entity>JpaRepository.kt` | `OrderJpaRepository.kt` |
| Command Service | `application/command/` | `<Verb><Noun>Service.kt` | `CreateOrderService.kt` |
| Command | `application/command/` | `<Verb><Noun>Command.kt` | `CreateOrderCommand.kt` |
| Query Service | `application/query/` | `<Verb><Noun>Service.kt` | `GetOrderService.kt` |
| Query 인터페이스 (읽기 최적화 분리 시) | `application/query/` | `<Aggregate>Query.kt` | `OrderQuery.kt` |
| Result | `application/{command,query}/` | `<Verb><Noun>Result.kt` | `GetOrderResult.kt` |
| Adapter 인터페이스 | `application/adapter/` | `<ExternalDomain>Adapter.kt` | `UserAdapter.kt` |
| Adapter 구현체 | `infrastructure/` | `<ExternalDomain>AdapterImpl.kt` | `UserAdapterImpl.kt` |
| 기술 인프라 Service 인터페이스 | `application/service/` | `<Concern>Service.kt` | `CryptoService.kt` |
| 기술 인프라 Service 구현체 | `infrastructure/` | `<Concern>ServiceImpl.kt` | `CryptoServiceImpl.kt` |
| Event Listener/Handler | `application/event/` | `<Domain><의미>Handler.kt` 또는 `<Domain>EventHandler.kt` | `OrderNotificationHandler.kt` |
| HTTP Controller | `interfaces/rest/` | `<Domain>Controller.kt` | `OrderController.kt` |
| Request/Response DTO 모음 | `interfaces/rest/` | `Schemas.kt` 또는 `<Domain>Schemas.kt` | `Schemas.kt` |
| `@ConfigurationProperties` | `config/` | `<Concern>Properties.kt` | `DatabaseProperties.kt` |
| `@Configuration` (Bean 팩토리) | `infrastructure/` | `<Concern>Config.kt` | `SesConfig.kt` |
| Scheduler | `infrastructure/` 또는 공유 `outbox/`/`scheduling/` | `<Concern>Relay.kt`/`<Concern>Scheduler.kt` | `OutboxRelay.kt` |

**주의**: root(TypeScript)/harness는 이 저장소의 관례로 kebab-case가 아니라 PascalCase 파일명을 정답으로 채택한다는 점을 다른 언어 구현체와 혼동하지 않는다 — [directory-structure.md](architecture/directory-structure.md) "파일 네이밍 규칙 — Kotlin/Java 관례" 절 참조.

REST 엔드포인트의 URL 경로 자체는 root 원칙대로 여전히 kebab-case를 사용한다(`/order-items`) — 이것은 파일명이 아니라 HTTP 경로 규칙이다. 8절 참조.

---

## 2. 클래스 네이밍 규칙

- Aggregate Root: 도메인 명사, `Order`, `Account`
- Value Object: 도메인 개념, `Money`, `OrderItem`
- Domain Event: 과거형 + `Event` 접미사, `OrderCreatedEvent`, `OrderCancelledEvent`
- 예외 계층: `sealed class <Domain>Exception`, 하위 클래스는 `<PascalCase 상황>Exception` — `OrderNotFoundException`, `OrderAlreadyCancelledException`
- 에러 코드 enum: `<Domain>ErrorCode`, 값은 `SCREAMING_SNAKE_CASE` — `ORDER_NOT_FOUND`
- Repository 인터페이스: `<Aggregate>Repository` (Kotlin `interface`, `abstract class` 아님)
- Repository 구현체: `<Aggregate>RepositoryImpl`
- Command Service: `<Verb><Noun>Service` — `CreateOrderService`, `CancelOrderService` (유스케이스 1개 = 클래스 1개)
- Query Service: `<Verb><Noun>Service` — `GetOrderService`, `GetOrdersService`
- Query 인터페이스(분리 시): `<Aggregate>Query`
- Command: `<Verb><Noun>Command` — `CancelOrderCommand`
- Result: `<Verb><Noun>Result` — `GetOrderResult`, `CreateOrderResult`
- Adapter 인터페이스: `<ExternalDomain>Adapter` — `UserAdapter`
- Adapter 구현체: `<ExternalDomain>AdapterImpl`
- 기술 인프라 Service 인터페이스: `<Concern>Service` — `CryptoService`, `NotificationService`, `StorageService`
- 기술 인프라 Service 구현체: `<Concern>ServiceImpl`
- Controller: `<Domain>Controller`
- `@ConfigurationProperties` data class: `<Concern>Properties` — `DatabaseProperties`, `JwtProperties`

각 유스케이스마다 별도 Service 클래스를 두는 것([cqrs-pattern.md](architecture/cqrs-pattern.md))이 Java의 `OrderService.create()/cancel()/...`처럼 한 클래스에 여러 유스케이스를 모으는 방식보다 이 저장소의 관례에 맞는다 — Kotlin의 생성자 주입 한 줄 선언 덕분에 클래스 수 증가 비용이 작다.

---

## 3. enum / 상수 배치

Kotlin은 root(TypeScript)의 "enum/상수를 별도 파일로 분리해 모듈 루트에 둔다"는 규칙을 그대로 따르되, 파일 하나에 반드시 톱레벨 선언 하나만 두어야 하는 것은 아니다 — 다만 이 저장소는 **레이어 성격이 다른 개념(도메인 상태 enum, 예외 계층, 에러 코드)을 각각 별도 `.kt` 파일로 분리**하는 것을 원칙으로 한다.

```kotlin
// domain/OrderStatus.kt — 도메인 상태값은 enum class로, 별도 파일
enum class OrderStatus { PENDING, PAID, CANCELLED }
```

```kotlin
// domain/OrderConstants.kt — 도메인 상수는 top-level const val 또는 object로 그룹화
const val MAX_ORDER_AMOUNT = 9_999_999L

object OrderPolicy {
    const val MAX_ITEMS_PER_ORDER = 100
}
```

Kotlin은 최상위 함수/상수 선언(top-level declaration)을 지원하므로, TypeScript처럼 `export const`를 흩뿌리는 대신 파일 하나가 곧 논리적 모듈이 된다 — `object`로 감싸는 것은 관련 상수를 이름공간으로 묶고 싶을 때만 사용한다.

Application 레이어(Command/Result/Query)에서 사용하는 enum도 동일하게 `domain/`에 정의하고 import해서 사용한다 — enum이 도메인 상태를 표현하는 한 domain/ 밖에 두지 않는다.

---

## 4. Kotlin 타이핑 패턴

### Aggregate/Entity — `private set` + `protected constructor()` + `companion object` 팩토리

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

상세 근거는 [tactical-ddd.md](architecture/tactical-ddd.md) 참조. **모든 프로퍼티는 `private set`**이며 외부에서 직접 대입할 방법이 없다 — 상태 변경은 도메인 메서드(`cancel()` 등)를 통해서만 이루어진다.

### Value Object — `data class` (`equals()` 수동 구현 불필요)

```kotlin
@Embeddable
data class Money(val amount: Long, val currency: String) {
    init {
        if (amount < 0) throw InvalidMoneyAmountException()
    }
    fun add(other: Money): Money = Money(amount + other.amount, currency)
}
```

root(TypeScript/Java)가 요구하는 "속성 기반 동등성 비교(`equals()`)"를 `data class` 키워드가 자동 생성한다 — 별도 구현 불필요. `init` 블록에서 불변식을 즉시 검증한다.

### Null-safety — `Optional<T>`/`T | undefined` 대신 `T?`

```kotlin
// 올바른 방식 — nullable 타입 + 엘비스 연산자
fun findByOrderId(orderId: String): Order? = jpaRepository.findByOrderId(orderId)

val order = orderRepository.findByOrderId(orderId) ?: throw OrderNotFoundException(orderId)
// 이후 order는 스마트 캐스트로 non-null Order 타입
```

Kotlin은 "DB nullable 필드는 `string | null`, optional 파라미터는 `?`" 같은 root(TypeScript)의 이원화된 표현이 필요 없다 — **모든 "값이 없을 수 있음"은 `T?` 하나로 통일**한다. 이 타입 하나가 "찾지 못함"(Repository 반환), "선택적 파라미터"(함수 인자 기본값 `= null`), "DB nullable 컬럼"(엔티티 프로퍼티) 세 가지 경우 모두를 표현하며, Java의 `Optional<T>` 래핑이나 TypeScript의 `| undefined` 구분이 필요 없다. `?:` 없이 nullable 값을 그대로 사용하려 하면 컴파일이 되지 않으므로 null 체크 누락이 원천 차단된다.

```kotlin
// 선택적 파라미터 — 기본값 null
fun getOrders(status: List<OrderStatus>? = null, page: Int = 0, take: Int = 20)

// DB nullable 컬럼
@Column
var completedAt: LocalDateTime? = null
    private set
```

`any` 사용 금지(root와 동일 원칙)에 대응하는 Kotlin 규칙은 **`Any`/`Any?` 타입을 도메인·Application 레이어의 공개 시그니처에 사용하지 않는다** — Domain Event를 담는 내부 컬렉션(`MutableList<Any>`)처럼 부득이한 경우가 아니라면, [domain-events.md](architecture/domain-events.md)가 권장하는 `sealed interface`로 구체 타입을 만든다.

### sealed class/interface — exhaustive `when`

```kotlin
sealed class OrderException(message: String, val code: OrderErrorCode, val httpStatus: HttpStatus) : RuntimeException(message)

class OrderNotFoundException(orderId: String) :
    OrderException("order not found: $orderId", OrderErrorCode.ORDER_NOT_FOUND, HttpStatus.NOT_FOUND)
```

같은 파일 내 상속만 허용되므로 컴파일러가 하위 타입 전체를 알고, `when (exception) { ... }` 분기에서 새 예외 추가 시 처리 누락을 컴파일 타임에 잡는다. 상세는 [error-handling.md](architecture/error-handling.md).

### 시간 값 — `LocalDateTime`, 타임존 변환 불필요

root(TypeScript)는 UTC ↔ KST 수동 변환 규칙을 규정하지만, Spring Boot는 `spring.jackson.time-zone` / DB 커넥션 타임존 설정으로 이 문제를 프레임워크 차원에서 해결한다 — 애플리케이션 코드에서 `LocalDateTime.now()` 이후 별도 변환 함수를 호출하지 않는다. 서버/DB 타임존 설정 자체를 변경하지 않는다는 원칙은 root와 동일하게 유지한다.

### 복잡한 타입 — nested `data class`

```kotlin
data class GetOrdersResult(val orders: List<OrderSummary>, val count: Long) {
    data class OrderSummary(val orderId: String, val status: String, val totalAmount: Long)
}
```

root의 `type OrderWithItems = Order & { items: OrderItem[] }` 같은 교차 타입 대신, Kotlin은 **nested data class**로 응답 스키마를 계층적으로 표현한다 — 별도 파일이나 static inner class + Lombok 없이 한 파일에서 완결된다.

---

## 5. REST API 엔드포인트 설계 규칙

URL 구조, HTTP 메서드/응답 코드, 비 CRUD 행위 표현, 중첩 리소스, kebab-case 규칙은 [루트 conventions.md](../../../docs/conventions.md) 1절과 동일하다 — 언어 무관 원칙이므로 반복하지 않는다. 이 절은 Kotlin/Spring MVC 구현 방식만 다룬다.

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

- `@ResponseStatus`(클래스 반환) 또는 `ResponseEntity<T>`(상태 코드를 동적으로 결정해야 할 때) 중 하나를 사용한다 — 정적으로 하나의 상태 코드만 반환하면 `@ResponseStatus`가 더 간결하다.
- 페이지네이션은 `@RequestParam(defaultValue = "0") page`, `@RequestParam(defaultValue = "20") take`로 표현한다 — 상세는 [api-response.md](architecture/api-response.md).
- 목록 응답의 키 이름은 도메인 객체 복수형(`orders`)이어야 한다 — `result`/`data`/`items` 금지.

---

## 6. 메서드 네이밍 및 구성

### Controller 메서드

- `create`, `get`, `find`, `cancel`, `close` 등 동사 사용, Spring MVC 애노테이션(`@PostMapping` 등)이 HTTP 메서드/경로를 결정하므로 메서드명 자체는 자유롭지만 root와의 일관성을 위해 동사 규칙을 유지한다.
- **`suspend fun`을 사용하지 않는다** — 이 저장소는 Spring MVC(블로킹) + Spring Data JPA(블로킹 드라이버) 조합이며 코루틴을 도입하지 않기로 결정했다([scheduling.md](architecture/scheduling.md) "코루틴 미사용 이유" 참조). 새 코드에 `suspend fun`을 도입하지 않는다.
- 반환 타입을 명시한다(공개 API 함수는 타입 추론에 맡기지 않는다) — `fun getOrder(...): GetOrderResult`.

### Service 클래스 구성 순서

1. 주 생성자 파라미터(Repository, 다른 Service 등 — `private val`로 선언과 동시에 필드가 된다)
2. `companion object`(있다면 — 상수, 정적 팩토리 등)
3. public 비즈니스 메서드
4. private 헬퍼 메서드

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

### `open` 관련 — 컴파일러 플러그인이 자동 처리

Kotlin 클래스는 기본 `final`이지만 `kotlin("plugin.spring")` 컴파일러 플러그인이 `@Component` 계열(`@Service`/`@Repository`/`@Configuration`) 애노테이션이 붙은 클래스를 자동으로 `open` 처리한다 — 소스에 `open` 키워드를 직접 쓰지 않는다([module-pattern.md](architecture/module-pattern.md) 참조).

### private 환경 분기 헬퍼

```kotlin
private fun paymentApiUrl(): String =
    if (activeProfile == "prod") "https://payment.api.example.com" else "https://payment.dev.api.example.com"
```

환경 분기가 필요한 값은 가능하면 `@ConfigurationProperties`로 옮기는 것이 우선이다([config.md](architecture/config.md)) — private 헬퍼 메서드는 설정으로 옮기기 애매한 경량 로직에만 사용한다.

---

## 7. import 구성 패턴

Kotlin/JVM 패키지는 항상 완전한 경로(fully-qualified)이므로 root(TypeScript)의 "상대경로 금지, `@/` alias 사용" 규칙 자체가 적용되지 않는다 — Kotlin에는 상대 import라는 개념이 없다. 대신 아래 규칙을 따른다.

- **와일드카드 import(`import com.example.orderservice.*`) 금지** — 클래스별로 개별 import한다. IntelliJ 기본 설정(`Settings > Editor > Code Style > Kotlin > Imports`)에서 "Use single name import"를 활성화해 자동 강제한다.
- **import 순서는 알파벳 순** — 표준 라이브러리(`kotlin.*`, `java.*`), 서드파티(`org.springframework.*`, `jakarta.*` 등), 프로젝트 내부 순으로 그룹을 나누되, ktlint/IntelliJ 기본 정렬(완전한 알파벳 순, 그룹 구분 없음)을 따라도 무방하다 — 이 저장소는 그룹 구분보다 **정렬 자동화 도구(ktlint)의 기본값을 그대로 신뢰**하는 것을 원칙으로 한다(수동으로 그룹을 맞추지 않는다).
- **사용하지 않는 import 제거** — IntelliJ의 "Optimize Imports" 또는 ktlint의 `no-unused-imports` 규칙으로 자동 정리한다.
- **`as` 별칭은 이름 충돌 시에만** — 같은 이름의 클래스가 다른 패키지에 있을 때(예: 여러 BC의 `Order` 클래스)만 `import com.example.userservice.Order as UserOrder`처럼 사용한다.

```kotlin
// 올바른 방식
import jakarta.persistence.Entity
import jakarta.validation.constraints.NotBlank
import org.springframework.stereotype.Service
import java.time.LocalDateTime

class CreateOrderService(/* ... */)
```

```kotlin
// 잘못된 방식 — 와일드카드
import jakarta.persistence.*
```

---

## 8. API 문서화 패턴 — springdoc-openapi

NestJS의 `@ApiProperty`/`@ApiOperation`(`@nestjs/swagger`)에 대응하는 것은 **springdoc-openapi**의 `@Schema`/`@Operation`이다.

```kotlin
@Operation(operationId = "createOrder", summary = "주문 생성")
@ApiResponse(responseCode = "201", description = "생성됨")
@PostMapping
@ResponseStatus(HttpStatus.CREATED)
fun createOrder(@Valid @RequestBody request: CreateOrderRequest): CreateOrderResult
```

```kotlin
data class CreateOrderRequest(
    @field:Schema(description = "주문 항목 목록", required = true)
    @field:Size(min = 1)
    val items: List<OrderItemRequest>,
)
```

### `@field:` 사용 지정자 — Kotlin data class + Bean Validation 필수 조합

```kotlin
data class CreateAccountRequest(
    @field:NotBlank
    @field:Email
    val email: String,
)
```

Kotlin은 생성자 파라미터의 애노테이션을 기본적으로 **파라미터 자체**에 붙이지만, Jakarta Bean Validation은 **필드**에 붙은 애노테이션만 검사한다 — `@field:` 사용 지정자(use-site target)를 빠뜨리면 컴파일 에러 없이 검증이 조용히 동작하지 않는다. 이 조합에서 가장 흔한 실수이므로 새 DTO를 작성할 때마다 확인한다([cross-cutting-concerns.md](architecture/cross-cutting-concerns.md) 참조).

### Deprecated 엔드포인트

```kotlin
@Operation(operationId = "createOrderLegacy", deprecated = true)
```

root와 동일하게 즉시 삭제하지 않고 `deprecated = true`로 표시해 마이그레이션 기간을 확보한다.

---

## 9. 로거 패턴

### `kotlin-logging` 권장 (신규 코드)

```kotlin
private val logger = KotlinLogging.logger {}

logger.info { "주문 생성 완료: orderId=$orderId" }   // 람다 지연 평가
```

`LoggerFactory.getLogger(Foo::class.java)`(SLF4J 직접 사용)도 여전히 동작하지만, 신규 코드는 `kotlin-logging`을 사용한다 — `::class.java` 보일러플레이트 제거, 람다 지연 평가로 비활성 로그 레벨의 문자열 조합 비용을 없앤다. 상세 비교는 [observability.md](architecture/observability.md) 참조.

### 구조화된 로그 — snake_case 필드

```kotlin
logger.atInfo()
    .addKeyValue("order_id", orderId)
    .addKeyValue("total_amount", totalAmount)
    .log("주문 생성 완료")
```

Kotlin 프로퍼티명은 camelCase(`orderId`)이지만 로그 필드명은 모니터링 플랫폼 관례에 맞춰 **snake_case**(`order_id`)를 사용한다 — root 원칙과 동일.

### Domain 레이어 로깅 금지

`Order`, `Money` 등 `domain/` 클래스는 로거를 import하지 않는다 — 로깅은 Application 레이어 이상에서만 수행한다.

---

## 10. 주석 스타일

- 비즈니스 도메인 설명은 한글 인라인 주석(`//`)으로 작성한다.
- **KDoc(`/** ... */`)은 공개 라이브러리성 API가 아니면 사용하지 않는다** — root의 "JSDoc 사용 안 함" 원칙과 동일하게, 애플리케이션 코드는 `//` 스타일만 사용한다.
- 긴 Service 메서드는 섹션 주석으로 논리적 구분을 표시한다.

```kotlin
fun cancel(command: CancelOrderCommand) {
    // 주문 조회 및 존재 검증
    val order = findOrderOrThrow(command.orderId)

    // 도메인 규칙 검증 및 상태 전이
    order.cancel(command.reason)

    // 영속화 (Outbox 포함)
    orderRepository.saveOrder(order)
}
```

---

## 11. 커밋 메시지 컨벤션

[루트 conventions.md](../../../docs/conventions.md) 2절의 Conventional Commits 규칙을 그대로 따른다 — 언어별로 다른 컨벤션을 만들지 않는다. scope에는 Kotlin 도메인 패키지명(`order`, `account` 등)을 사용한다.

```
feat(order): 주문 취소 기능 추가
fix(order): 계좌 잔액 조회 시 soft-delete된 거래가 포함되는 현상 수정
refactor(order): Repository 메서드명을 find/save/delete<Noun> 패턴으로 통일
test(order): Order 불변식 단위 테스트 추가
```

---

## 12. 브랜치 및 PR 컨벤션

[루트 conventions.md](../../../docs/conventions.md) 3절과 동일하다 — `<type>/<scope>-<description>`, 모든 단어 kebab-case, `main`에서 분기, `main` 직접 push 금지, Squash and merge.

---

## 13. 테스트 패턴

상세 근거와 전체 예시는 [testing.md](architecture/testing.md) 참조. 이 절은 컨벤션만 요약한다.

### 프레임워크 — JUnit 5 + MockK + AssertJ + Testcontainers

```kotlin
// build.gradle.kts
testImplementation("io.mockk:mockk:1.13.13")
```

Mockito 대신 **MockK**를 사용한다 — Kotlin `final` 클래스와 자연스럽게 맞물리고(`interface` Repository는 어느 쪽이든 mock 가능하지만, 이 저장소는 Kotlin 관용 통일을 위해 MockK로 고정한다), `every {}`/`verify {}` DSL이 `data class` Command/Result와 자연스럽게 어우러진다.

### 3계층 테스트 — Domain(순수) → Application(MockK) → E2E(Testcontainers)

```kotlin
// Domain 단위 테스트 — 프레임워크 없이 순수 Kotlin
class OrderTest {
    @Test
    fun `주문 항목이 비어있으면 생성 시 예외를 던진다`() {
        assertThrows<OrderItemsEmptyException> { Order.create("user-1", emptyList()) }
    }
}
```

```kotlin
// Application 단위 테스트 — MockK로 Repository 대체
class CancelOrderServiceTest {
    private val orderRepository = mockk<OrderRepository>(relaxed = true)
    private val service = CancelOrderService(orderRepository)

    @Test
    fun `존재하지 않는 주문을 취소하면 예외를 던진다`() {
        every { orderRepository.findOrders(any()) } returns (emptyList<Order>() to 0L)
        assertThrows<OrderNotFoundException> { service.cancel(CancelOrderCommand("no-such-id", "변심")) }
    }
}
```

### 테스트 파일 배치 — Gradle 표준 소스셋 레이아웃

```
src/main/kotlin/.../order/domain/Order.kt
src/test/kotlin/.../order/domain/OrderTest.kt
src/test/kotlin/.../order/application/command/CancelOrderServiceTest.kt
src/test/kotlin/.../order/interfaces/rest/OrderControllerE2ETest.kt
```

NestJS/Jest처럼 소스 파일 바로 옆에 `.spec.ts`를 두지 않는다 — `src/main`과 `src/test`를 분리하고 동일한 패키지 구조를 미러링하는 것이 Gradle/Maven의 표준 관례다.

### 테스트 네이밍 — 한글 backtick 함수명

```kotlin
@Test
fun `이미 취소된 주문을 다시 취소하면 예외를 던진다`() { /* ... */ }
```

root의 `{도메인행위}_when_{조건}_then_{기대결과}` 스네이크 케이스 대신, Kotlin은 백틱으로 감싼 **완전한 한글 문장**으로 테스트 의도를 표현한다 — 이 저장소가 이미 채택한 관례([testing.md](architecture/testing.md))이며 새 테스트도 이를 따른다.

### E2E — Testcontainers, in-memory DB 금지

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

H2 in-memory 같은 대체 DB 대신 **Testcontainers로 실제 Postgres**를 띄운다 — SQL 방언 차이로 인한 거짓 양성/음성을 피한다.

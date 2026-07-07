# AI Agent 자기 검토 체크리스트 — Kotlin Spring Boot

작업 완료 후, 아래 체크리스트를 순서대로 검토한다.
각 항목을 점검하여 위반이 발견되면 즉시 수정한 뒤 다음 항목으로 넘어간다.

### 검증 수행 규칙

- 각 STEP을 검증할 때 반드시 해당 파일을 Read 도구로 읽고 실제 코드와 대조한다.
- 코드를 읽지 않고 통과 처리하는 것은 금지한다.
- 위반 발견 시 즉시 수정한 후 다음 STEP으로 넘어간다.
- `examples/`의 기존 코드가 아래 항목과 다르게 되어 있다고 해서 통과 처리하지 않는다 — `examples/`는 문서가 명시한 "알려진 갭"을 아직 반영하지 않은 상태이며, 이 체크리스트가 요구하는 것은 각 `architecture/*.md`가 정의하는 올바른 패턴이다.

---

## STEP 1 — 파일 구조 및 네이밍

**관련 문서**: [conventions.md](./conventions.md) · [architecture/directory-structure.md](./architecture/directory-structure.md)

```
[ ] 파일명이 PascalCase(파일명 = 최상위 public 클래스명)가 아닌 것이 있는가?
    → 있다면 PascalCase.kt로 변경 (kebab-case 아님 — Kotlin/Java 관례)
[ ] 파일 하나에 최상위 public 클래스가 2개 이상 있는가?
    → 파일당 클래스 1개로 분리
[ ] enum class가 다른 파일 내에 인라인으로 선언되어 있는가?
    → domain/ 아래 <Concept>.kt 파일로 분리
[ ] 도메인 상수가 다른 파일 내에 인라인으로 선언되어 있는가?
    → domain/ 아래 <Domain>Constants.kt 또는 top-level const val로 분리
[ ] Repository 인터페이스 파일명이 <Aggregate>Repository.kt 형식인가? (domain/ 레이어)
[ ] Repository 구현체 파일명이 <Aggregate>RepositoryImpl.kt 형식인가? (infrastructure/persistence/ 레이어)
[ ] Domain 레이어 파일명이 규칙을 따르는가?
    → Aggregate Root: <AggregateRoot>.kt, Value Object: <ValueObject>.kt, Domain Event: <PascalCase 과거형>Event.kt
[ ] 예외 계층 파일명이 <Domain>Exception.kt(sealed class + 하위 클래스 한 파일) 형식인가?
[ ] 에러 코드 파일명이 <Domain>ErrorCode.kt 형식인가?
[ ] Command Service/Query Service 파일명이 <Verb><Noun>Service.kt 형식인가? (유스케이스 1개 = 클래스 1개)
[ ] Command/Result 파일명이 <Verb><Noun>Command.kt / <Verb><Noun>Result.kt 형식인가?
[ ] Adapter 파일명이 규칙을 따르는가?
    → 인터페이스: <ExternalDomain>Adapter.kt (application/adapter/)
    → 구현체: <ExternalDomain>AdapterImpl.kt (infrastructure/)
[ ] 기술 인프라 Service 파일명이 규칙을 따르는가?
    → 인터페이스: <Concern>Service.kt (application/service/)
    → 구현체: <Concern>ServiceImpl.kt (infrastructure/)
[ ] Controller 파일명이 <Domain>Controller.kt 형식이고 interfaces/rest/ 에 위치하는가? (interface 단수 아님 — 이 저장소는 복수 interfaces/)
[ ] @ConfigurationProperties data class 파일명이 <Concern>Properties.kt 형식이고 config/ 에 위치하는가?
[ ] 클래스명이 네이밍 규칙을 따르는가? (conventions.md 2절 — Aggregate/VO/Event/Repository/Service/Command/Result/Adapter/ErrorCode 네이밍)
[ ] harness의 file-naming 검사(^[A-Z][A-Za-z0-9]*$)를 통과하는가? (./harness/harness.sh 로 확인)
```

---

## STEP 2 — Domain 레이어

**관련 문서**: [architecture/layer-architecture.md](./architecture/layer-architecture.md) · [architecture/tactical-ddd.md](./architecture/tactical-ddd.md) · [architecture/domain-service.md](../../../docs/architecture/domain-service.md) (루트 공용) · [architecture/aggregate-id.md](./architecture/aggregate-id.md)

```
[ ] domain/ 디렉토리에 Aggregate Root, Value Object, Domain Event, Repository 인터페이스가 있는가?
[ ] Aggregate Root에 비즈니스 규칙과 불변식이 캡슐화되어 있는가?
    → Application Service에 상태 변경 조건 검사나 계산 로직이 있다면 Aggregate 도메인 메서드로 이동
[ ] Aggregate/Entity 생성자가 protected constructor()이고, 공개 생성 경로가 companion object의 팩토리 함수(create()) 하나뿐인가?
    → 외부 코드가 Order()로 빈 인스턴스를 만들 수 있다면 위반
[ ] Aggregate/Entity의 모든 프로퍼티가 private set인가?
    → var 뒤에 private set이 없는 프로퍼티가 있다면, 외부에서 직접 대입 가능한지 확인하고 수정
[ ] Domain 레이어 파일에 @Service/@Component/@Repository/@Controller/@RestController가 사용되었는가?
    → 있다면 제거. Domain 레이어는 Spring 무의존 (JPA 애노테이션 @Entity/@Embeddable/@Column 등은 이 저장소의 확립된 관례로 예외 허용, directory-structure.md 참조)
[ ] Repository 인터페이스가 Kotlin interface로 정의되어 있는가? (abstract class 아님 — Spring은 interface 자체가 DI 토큰)
[ ] Repository 인터페이스가 domain/ 레이어에 위치하는가?
[ ] Aggregate 간 직접 참조 없이 ID(String) 참조만 사용하는가?
[ ] Aggregate 외부에서 내부 상태를 직접 변경하는 코드가 있는가? (private set으로 컴파일 차단되어야 함)
[ ] Domain Service가 있다면 domain/ 레이어에 위치하고 Spring 애노테이션 없이 작성되어 있는가?
    → 상태를 갖지 않고, Repository를 직접 호출하지 않는가? (domain-service.md의 "잘못 쓰는 패턴" 참조)
[ ] Value Object가 data class로 정의되어 equals()/hashCode()가 자동 생성되는가?
[ ] Value Object/Aggregate의 불변식이 init { } 블록 또는 companion object.create() 내부에서 즉시 검증되는가?
[ ] Aggregate 생성 시 ID가 generateId()(UUID v4, 하이픈 제거, 32자리 hex 문자열)로 생성되는가?
    → UUID.randomUUID().toString()을 하이픈 포함 그대로 쓰고 있다면 위반 (aggregate-id.md의 "알려진 갭" 참조 — 새 코드에서 반복하지 않는다)
[ ] JPA surrogate key(id: Long?, @GeneratedValue)와 도메인 식별자(accountId/orderId: String)가 분리되어 있는가?
    → Controller/Command/Result 등 외부에 Long 타입 id가 노출되지 않아야 한다
[ ] 하위 Entity(Transaction 등)도 Aggregate Root와 동일하게 protected constructor() + companion object.create() + generateId() 패턴을 따르는가?
[ ] 하위 Entity가 Aggregate Root의 Repository를 통해서만 저장/조회되는가? (별도 Repository 없음)
```

---

## STEP 3 — 레이어 아키텍처 / CQRS / Domain Event

**관련 문서**: [architecture/layer-architecture.md](./architecture/layer-architecture.md) · [architecture/cqrs-pattern.md](./architecture/cqrs-pattern.md) · [architecture/domain-events.md](./architecture/domain-events.md) · [architecture/cross-domain.md](./architecture/cross-domain.md)

```
[ ] Controller가 Service 호출 외에 비즈니스 로직을 수행하는가?
    → 있다면 Service 또는 Aggregate로 이동
[ ] Application Service(Command/Query)가 비즈니스 로직을 직접 수행하는가? (상태 변경 조건 검사, 금액 계산 등)
    → 있다면 Aggregate의 도메인 메서드로 이동
[ ] Command Service에 @Service가 붙어 있는가? (@Transactional은 없어야 한다 — 트랜잭션 경계는 Repository.save()로 이관됨, 아래 STEP 8 참고)
[ ] Query Service에 @Service + @Transactional(readOnly = true)가 붙어 있는가?
[ ] Command Service가 Repository만, Query Service가 (분리했다면) Query 인터페이스만 사용하는가?
    → Query Service가 Repository를 직접 사용 중이라면, 읽기 전용 프로젝션이 필요한 시점인지 판단 (cqrs-pattern.md의 "알려진 차이" 참조 — 조회량이 적고 읽기 모델이 Aggregate와 거의 같다면 실용적 단순화로 허용)
[ ] Query 인터페이스를 별도로 두었다면 application/query/에 interface로, 구현체는 infrastructure/에 두었는가?
[ ] Repository 구현체 생성자에 EntityManager/JpaRepository가 주입되고, Domain/Application은 이를 전혀 알지 못하는가?
[ ] 레이어 의존 방향이 올바른가? (interfaces → application → domain ← infrastructure)
    → 하위 레이어가 상위 레이어를 import하는 코드가 있다면 수정
[ ] Aggregate 내부의 하위 Entity를 Aggregate Root의 Repository를 통해 함께 저장/조회하는가?
    → 하위 Entity에 별도 Repository를 만들지 않는다
[ ] Domain Event는 Aggregate 내부 도메인 메서드에서만 수집되는가? (domainEvents 리스트 + pullDomainEvents())
    → Command Service가 직접 이벤트 객체를 생성하지 않는다
[ ] Domain Event 발행이 ApplicationEventPublisher.publishEvent()에 의한 동기 in-process 방식이 아닌가?
    → 그 방식이라면 위반. Repository.save<Noun>() 내부에서 Outbox 테이블에 함께 저장하고, Command Service는 저장 직후 OutboxRelay.processPending()만 호출한다 (domain-events.md 참조)
[ ] Repository 구현체의 save<Noun>() 메서드가 @Transactional로 Aggregate 저장 + Outbox 저장을 하나의 트랜잭션으로 묶는가?
[ ] OutboxRelay가 Command Service의 저장 직후 동기 호출로 드레인되는가? (`@Scheduled` 폴링이 아니다 — domain-events.md 참조)
[ ] 이벤트 타입이 여러 개라면 sealed interface로 묶어 when 분기의 완전성(exhaustiveness)을 컴파일러가 검사하게 했는가?
[ ] Event Handler(`*EventHandler`, outbox가 드레인한 이벤트 처리)가 application/event/ 패키지에 위치하는가?
    → harness의 event-placement 검사 대상
[ ] 이벤트 핸들러가 멱등하게 구현되어 있는가? (at-least-once 전달 전제 — 이미 처리된 eventId는 skip)
[ ] 크로스 도메인 호출이 필요한 경우, Adapter 인터페이스(application/adapter/, Kotlin interface)를 통해 호출하는가?
    → 다른 BC의 Service/Repository를 직접 주입받고 있다면 Adapter로 전환
[ ] Adapter가 조회 전용인가? (외부 BC의 상태 변경 메서드를 호출하지 않는다 — 쓰기는 Integration Event로 전환)
```

---

## STEP 4 — Repository 패턴

**관련 문서**: [architecture/repository-pattern.md](./architecture/repository-pattern.md) · [architecture/persistence.md](./architecture/persistence.md)

```
[ ] Repository가 Aggregate Root 단위로 정의되어 있는가? (Entity/테이블 단위 X)
[ ] Repository 인터페이스가 domain/에, 구현체가 infrastructure/persistence/에 있는가?
[ ] Repository 메서드명이 find<Noun>s / save<Noun> / delete<Noun> 패턴 하나로 통일되어 있는가?
    → findByXxxAndYyy 같은 전용 조합 메서드가 늘어나고 있다면 find<Noun>s(query: <Noun>FindQuery) 하나로 통합
[ ] 단건 조회를 위해 별도 findOne/findById류 메서드를 만들었는가?
    → 있다면 제거. find<Noun>s(take = 1) 결과에 .firstOrNull()을 사용
[ ] find<Noun>s의 반환 타입이 목록과 count를 함께 반환하는가? (Pair<List<T>, Long> 또는 전용 data class)
    → 목록과 count를 별도 메서드(findAll/countAll)로 분리해두지 않았는가
[ ] Repository에 update<Noun> 메서드가 있는가?
    → 있다면 제거. 조회 후 Aggregate 도메인 메서드로 상태 변경, save<Noun>으로 저장
[ ] delete<Noun>()가 soft delete(deletedAt 갱신)로 구현되어 있는가? (hard delete 금지)
[ ] deletedAt 컬럼이 존재한다면, 모든 조회 쿼리에 deletedAt IS NULL 조건이 기본 적용되는가?
[ ] Repository 구현체가 DB 레코드(Entity)를 그대로 반환하지 않고 도메인 Aggregate로 변환하는가?
    → 이 저장소는 JPA Entity = Aggregate이므로 별도 변환이 필요 없는 경우가 많다 — 다만 Query 결과(Result data class)는 항상 별도 변환
[ ] 동적 필터 조건이 값이 있을 때만 조건을 추가하는 방식(JPQL 조건부 append 또는 QueryDSL/Criteria 조건부 체이닝)으로 구현되어 있는가?
```

---

## STEP 5 — Spring DI / 컴포넌트 스캔

**관련 문서**: [architecture/module-pattern.md](./architecture/module-pattern.md) · [architecture/shared-modules.md](./architecture/shared-modules.md) · [architecture/bootstrap.md](./architecture/bootstrap.md)

```
[ ] @Service가 application/{command,query}/ 안에만 있는가? (harness의 service-annotation 검사)
[ ] @Repository가 infrastructure/ 안에만 있는가? (harness의 repository-annotation 검사)
[ ] @RestController가 interfaces/ 안에만 있는가? (harness의 controller-placement 검사)
[ ] 도메인(Bounded Context)이 패키지 단위로 구성되어 있는가? (레이어 단위로 최상위 패키지를 나누지 않는다)
[ ] 하나의 도메인 패키지 안에 domain/application/infrastructure/interfaces 4개 레이어가 모두 포함되어 있는가? (harness의 package-structure 검사)
[ ] application/ 아래에 command/와 query/ 서브패키지가 모두 존재하는가? (harness의 package-structure 검사)
[ ] 생성자 주입을 사용하는가? (주 생성자 파라미터 = DI 대상, @Autowired 불필요)
    → 필드 주입(@Autowired lateinit var)을 사용하고 있다면 생성자 주입으로 전환
[ ] 다른 도메인의 Service/Repository를 Application Service 생성자에서 직접 주입받고 있는가?
    → 있다면 Adapter 패턴으로 전환 (application/adapter/ interface + infrastructure/ 구현체)
[ ] 순환 의존(A → B → A)이 있어 애플리케이션이 기동 실패하는가?
    → @Lazy로 임시 우회하지 말고 Adapter 단방향화, 공통 로직 추출, BC 경계 재검토로 근본 해결
[ ] @Bean 팩토리가 필요한 서드파티 클라이언트(SesClient, S3Presigner 등)가 @Configuration 클래스에 정의되어 있는가?
[ ] 여러 BC가 공유해야 하는 코드(ID 생성, 에러 핸들러, Outbox, 인증)가 shared-modules.md가 제안하는 common/·config/·auth/·outbox/ 패키지에 배치되어 있는가?
    → BC 소속 Technical Service(notification 등)를 성급하게 공유 패키지로 승격하지 않았는가
```

---

## STEP 6 — Kotlin 타이핑 / Null-safety

**관련 문서**: [conventions.md](./conventions.md) 4절

```
[ ] Command/Result/Query 클래스가 data class로 정의되어 있는가? (Object.assign 패턴 불필요 — 주 생성자가 이를 대체)
[ ] Any/Any? 타입을 도메인·Application 공개 시그니처에 사용한 곳이 있는가?
    → 있다면 구체 타입 또는 sealed interface로 교체
[ ] "찾지 못함"/선택적 값이 T?(nullable)로 표현되어 있는가? (Optional<T> 래핑 사용 금지)
[ ] nullable 값을 ?: 없이 강제 언랩(!!)하는 코드가 있는가?
    → 있다면 제거. ?: 엘비스 연산자 또는 스마트 캐스트로 안전하게 처리
[ ] 도메인 상태값이 enum class로 정의되어 있는가? (String 상수 나열 금지)
[ ] Service 메서드의 반환 타입이 명시되어 있는가? (공개 API에 타입 추론만 맡기지 않는다)
[ ] 와일드카드 import를 사용한 곳이 있는가?
    → 있다면 개별 import로 교체
[ ] suspend fun을 사용한 곳이 있는가?
    → 있다면 제거. 이 저장소는 코루틴을 도입하지 않기로 결정했다 (scheduling.md 참조)
```

---

## STEP 7 — 에러 처리

**관련 문서**: [architecture/error-handling.md](./architecture/error-handling.md)

```
[ ] 도메인 예외 계층이 sealed class <Domain>Exception : RuntimeException(message)으로 정의되어 있는가?
[ ] sealed class 예외 계층이 domain/ 레이어에 위치하는가? (harness의 sealed-exception 검사)
[ ] 각 하위 예외 클래스가 <Domain>ErrorCode enum 값과 1:1로 매핑되어 있는가?
    → 코드 없이 메시지만 있는 예외가 있다면 ErrorCode 추가
[ ] <Domain>ErrorCode의 모든 값이 SCREAMING_SNAKE_CASE인가?
[ ] 에러 응답 바디가 { statusCode, code, message, error } 4개 필드 형식을 따르는가?
    → message 필드 하나뿐인 ErrorResponse가 있다면 4필드로 확장
[ ] 에러 변환이 @RestControllerAdvice(전역 핸들러, common/GlobalExceptionHandler.kt)로 일원화되어 있는가?
    → 각 Controller에 @ExceptionHandler가 개별적으로 흩어져 있다면 전역 핸들러로 통합
[ ] MethodArgumentNotValidException(Bean Validation 실패)이 code: "VALIDATION_FAILED"로 4필드 형식에 맞게 처리되는가?
[ ] 분류되지 않은 예외(Exception::class)를 마지막에 잡아 500으로 캐치-올하는 핸들러가 있는가?
    → 없다면 Spring 기본 HTML 에러 페이지가 API 클라이언트에 노출될 위험
[ ] Domain/Application에서 HttpException/ResponseStatusException 등 HTTP 예외를 직접 throw하는가?
    → 있다면 sealed class 예외로 교체. HTTP 상태 코드 변환은 Interface 레이어(@RestControllerAdvice)의 책임
```

---

## STEP 8 — REST API 엔드포인트

**관련 문서**: [conventions.md](./conventions.md) 5절 · [architecture/api-response.md](./architecture/api-response.md) · [architecture/authentication.md](./architecture/authentication.md) · [architecture/rate-limiting.md](./architecture/rate-limiting.md)

```
[ ] URL이 동사가 아닌 복수 명사 리소스로 구성되어 있는가? (GET /orders, POST /orders)
[ ] URL이 kebab-case 소문자만 사용하는가? (/order-items — 파일명의 PascalCase와 혼동하지 않는다)
[ ] HTTP 메서드가 올바르게 사용되는가? (GET 조회, POST 생성, PUT 전체수정, PATCH 부분수정, DELETE 삭제)
[ ] 응답 코드가 HTTP 메서드에 맞는가? (GET/PUT/PATCH 200, POST 201, DELETE 204)
[ ] 비 CRUD 행위가 하위 리소스 경로로 표현되는가? (POST /orders/{orderId}/cancel)
[ ] 중첩 리소스가 2단계 이내인가?
[ ] 목록 조회 응답의 키 이름이 도메인 객체명 복수형인가? ({ orders: [...], count: N })
[ ] 페이지네이션이 @RequestParam(defaultValue = "0") page / @RequestParam(defaultValue = "20") take로 표현되어 있는가?
[ ] 인증이 필요한 Controller가 Spring Security Filter Chain(authorizeHttpRequests에서 anyRequest, authenticated)으로 보호되는가?
    → 메서드/클래스 레벨 애노테이션이 아니라 SecurityConfig의 경로 패턴 화이트리스트 방식을 따르는가
[ ] Controller가 SecurityContext/Authentication에서 인증된 사용자 ID만 꺼내 Command에 포함하는가?
    → 클라이언트가 보낸 헤더(X-User-Id 등)를 검증 없이 그대로 신뢰하고 있다면 위반
[ ] JWT payload에 최소 정보(subject=userId)만 담고 있는가? (email/role 등을 토큰에 캐시하지 않는다)
[ ] Rate Limiting이 Filter 기반(OncePerRequestFilter)으로 인증/검증보다 앞서 적용되어 있는가?
```

---

## STEP 9 — API 문서화 (springdoc-openapi)

**관련 문서**: [conventions.md](./conventions.md) 8절 · [architecture/bootstrap.md](./architecture/bootstrap.md)

```
[ ] 모든 Controller 메서드에 @Operation(operationId = "...")이 있는가?
[ ] 응답 스키마가 필요한 엔드포인트에 @ApiResponse(responseCode, description)가 있는가?
[ ] Request DTO(data class)의 Bean Validation 애노테이션에 @field: 사용 지정자가 붙어 있는가?
    → 빠뜨리면 컴파일 에러 없이 검증이 조용히 동작하지 않는다 (가장 흔한 실수)
[ ] Request/Response DTO 필드에 @Schema(description = "...")가 있는가?
[ ] 사용 중단 예정 엔드포인트에 @Operation(deprecated = true)가 표시되어 있는가?
[ ] springdoc-openapi 의존성이 build.gradle.kts에 추가되어 있는가? (@Operation/@Schema를 사용하는 경우)
```

---

## STEP 10 — import 구성

**관련 문서**: [conventions.md](./conventions.md) 7절

```
[ ] 와일드카드 import(import x.y.*)를 사용한 곳이 있는가?
    → 있다면 개별 import로 교체
[ ] 사용하지 않는 import가 남아있는가?
    → IntelliJ "Optimize Imports" 또는 ktlint로 정리
[ ] 이름 충돌이 없는데 as 별칭을 불필요하게 사용하고 있는가?
```

---

## STEP 11 — 스테레오타입 애노테이션 / 횡단 관심사

**관련 문서**: [architecture/module-pattern.md](./architecture/module-pattern.md) · [architecture/cross-cutting-concerns.md](./architecture/cross-cutting-concerns.md) · [architecture/authentication.md](./architecture/authentication.md) · [architecture/scheduling.md](./architecture/scheduling.md) · [architecture/domain-events.md](./architecture/domain-events.md)

```
[ ] Correlation ID Filter(OncePerRequestFilter, @Order(Int.MIN_VALUE))가 필터 체인 최상단에 등록되어 있는가?
[ ] MDC.put()으로 넣은 Correlation ID가 finally 블록에서 MDC.remove()로 정리되는가?
    → 스레드 풀 재사용 시 컨텍스트 오염 방지
[ ] 인증 Filter(JwtAuthenticationFilter)가 Correlation ID 다음, 컨트롤러 이전에 위치하는가?
[ ] 요청 로깅이 HandlerInterceptor로 구현되어 있는가? (Filter가 아니라 — Handler 정보가 필요하므로)
[ ] Domain 레이어에서 Filter/Interceptor/Logger를 import하고 있지 않은가?
[ ] @Scheduled 작업이 infrastructure/ 또는 공유 outbox/·scheduling/ 패키지에 위치하는가?
    → Application/Domain 레이어에 스케줄링 애노테이션 사용 금지
[ ] Scheduler(@Scheduled 메서드)가 비즈니스 로직을 직접 실행하지 않고 적재(enqueue)만 하는가?
[ ] @Scheduled 메서드의 예외가 runCatching { }.onFailure { logger.error(...) }로 명시적으로 로깅되는가?
    → Spring이 예외를 로그만 남기고 삼키므로, 명시적 로깅 없이는 실패가 관찰되지 않는다
[ ] 여러 인스턴스 환경에서 동일 Cron 타이밍의 중복 적재를 날짜/엔티티 기반 deduplicationId로 방지하는가?
[ ] Command 트랜잭션 안에서 Task Outbox 적재가 함께 이루어지는가? (DB 변경과 Task 적재의 원자성, dual-write 방지)
[ ] ThreadPoolTaskScheduler에 setWaitForTasksToCompleteOnShutdown(true)가 설정되어 있는가? (graceful-shutdown 대응)
```

---

## STEP 12 — DB / 인프라 패턴

**관련 문서**: [architecture/persistence.md](./architecture/persistence.md) · [architecture/config.md](./architecture/config.md) · [architecture/secret-manager.md](./architecture/secret-manager.md) · [architecture/local-dev.md](./architecture/local-dev.md) · [architecture/container.md](./architecture/container.md) · [architecture/graceful-shutdown.md](./architecture/graceful-shutdown.md) · [architecture/observability.md](./architecture/observability.md)

```
[ ] Command Service 메서드에 @Transactional이 없는가? (트랜잭션 경계는 Repository.save() 쪽에 있어야 한다), Query Service 메서드에 @Transactional(readOnly = true)가 붙어 있는가?
[ ] Repository.save()에 @Transactional이 있고, 여러 Repository에 걸친 쓰기 작업이 하나의 @Transactional 메서드 안에서 실행되는가?
[ ] Entity 공통 컬럼(createdAt/updatedAt/deletedAt)이 @MappedSuperclass BaseEntity로 추출되어 있는가? (Entity가 2개를 넘어가는 시점부터)
[ ] soft delete가 실제로 배선되어 있는가? (deletedAt 컬럼만 있고 delete<Noun>() 실행 경로가 없는 죽은 컬럼이 아닌가)
[ ] 프로덕션 프로파일이 ddl-auto: update/create-drop이 아니라 validate로 설정되어 있는가?
[ ] Flyway 마이그레이션 파일이 Entity 변경에 맞춰 추가되어 있는가? (db/migration/V<N>__*.sql)
[ ] 관심사별 설정이 @ConfigurationProperties + data class로 분리되어 있는가? (@Value 문자열 키 산발적 사용 금지)
[ ] 기본값 없는 필수 설정 필드로 Fail-fast(바인딩 실패 시 기동 중단)가 자연스럽게 보장되는가?
    → 필요 시 @Validated + @field:NotBlank로 빈 문자열까지 차단
[ ] 설정 값(DatabaseProperties 등)이 Infrastructure 레이어에서만 주입되는가? (Domain/Application 생성자에 직접 주입 금지)
[ ] DB 비밀번호·JWT secret 등 민감값이 코드/커밋에 하드코딩되어 있지 않은가?
[ ] 프로덕션에서 민감값을 SecretService(Technical Service 패턴, TTL 캐시 포함)로 조회하는가?
[ ] 로컬 docker-compose의 모든 인프라 서비스에 실제 API 기반 healthcheck가 설정되어 있는가?
[ ] Dockerfile이 멀티스테이지(Gradle 빌드 스테이지 + JRE 런타임 스테이지)로 구성되어 있는가?
[ ] Dockerfile의 CMD가 exec form(["java", "-jar", "app.jar"])인가? (shell form 금지 — SIGTERM 전달 지연 방지)
[ ] server.shutdown: graceful + spring.lifecycle.timeout-per-shutdown-phase가 설정되어 있는가?
[ ] Actuator liveness/readiness 프로브가 활성화되어 있는가? (management.health.livenessstate/readinessstate)
[ ] 구조화 로그가 snake_case 필드명을 사용하는가? (모니터링 연동 시)
[ ] Correlation ID가 MDC를 통해 로그 전반에 자동 전파되는가?
```

---

## STEP 13 — 테스트 패턴

**관련 문서**: [conventions.md](./conventions.md) 13절 · [architecture/testing.md](./architecture/testing.md)

```
[ ] Domain 레이어 단위 테스트가 Spring 없이 순수 Kotlin으로 작성되어 있는가? (@SpringBootTest 없이 Aggregate.create() 직접 호출)
[ ] Application Service 테스트에서 Repository를 MockK(mockk<AccountRepository>())로 대체하는가?
[ ] E2E 테스트가 Testcontainers(실제 Postgres, 필요 시 LocalStack)로 작성되어 있는가? (H2 등 in-memory 대체 DB 금지)
[ ] Aggregate 불변식 위반 테스트가 작성되어 있는가? (잘못된 입력 → sealed class 예외 발생)
[ ] Domain Event 수집 여부를 검증하는 테스트가 있는가? (pullDomainEvents() 결과 검증)
[ ] 테스트 파일이 src/test/kotlin 아래 동일한 패키지 구조로 배치되어 있는가? (소스 옆이 아니라 별도 소스셋)
[ ] 테스트 함수명이 백틱으로 감싼 한글 문장인가? (`계좌 생성 시 잔액은 0이다`() 형식)
[ ] MockK의 relaxed mock/slot 캡처 등이 적절히 사용되어 반환값 없는 메서드마다 매번 every {} returns Unit을 반복하지 않는가?
```

---

## STEP 14 — 전체 일관성 최종 확인

**관련 문서**: [conventions.md](./conventions.md) · [architecture/design-principles.md](./architecture/design-principles.md)

```
[ ] @RestControllerAdvice에서 처리되는 모든 예외가 실제로 sealed class 계층의 하위 타입으로 커버되는가?
    → 새 예외를 추가했다면 handler의 when/매핑에 반영되었는지 확인
[ ] 새로 추가한 클래스에 올바른 스테레오타입 애노테이션(@Service/@Repository/@Component/@RestController)이 붙어 있고 컴포넌트 스캔 대상 패키지 안에 있는가?
[ ] Command/Query/Result 객체를 새로 만들었다면 data class이고 Application 레이어(command/ 또는 query/)에 위치하는가?
[ ] 작업한 코드에 TODO, println, 임시 주석이 남아있지 않은가?
[ ] 유비쿼터스 언어가 코드(클래스명, 메서드명, 프로퍼티명)에 일관되게 반영되어 있는가?
[ ] 주석 스타일이 // 인라인 주석만 사용하는가? (KDoc 사용 금지)
[ ] 로거 출력이 구조화된 형태인가? (외부 모니터링 연동 시 snake_case 필드명)
[ ] 커밋 메시지가 Conventional Commits 형식(feat/fix/refactor + scope)을 따르는가?
[ ] 커밋 메시지의 scope가 서비스 도메인명(order, account 등)인가?
[ ] 커밋 메시지의 description이 한글·서술형이며 끝에 마침표가 없는가?
[ ] 브랜치명이 Conventional Branch 형식(<type>/<scope>-<description>)이고 모든 단어 kebab-case인가?
[ ] main 브랜치에 직접 commit/push하지 않고 PR을 통해 반영하는가?
[ ] PR 제목이 Conventional Commits 형식과 동일하고, 본문이 Summary + Test plan 형식을 따르는가?
[ ] 머지 전략이 Squash and merge인가?
[ ] harness.sh 실행 결과가 FAIL 없이 통과하는가? (./harness/harness.sh <projectRoot>)
```

---

## STEP 15 — 설계 산출물 형태 (설계 단계 작업인 경우)

**관련 문서**: [development-process.md](../../../docs/development-process.md) (루트 공용) · [reference.md](./reference.md)

> 설계 단계(RA, SD, DM, TD) 산출물을 작성한 경우에만 적용한다. 산출물 형식 자체는 프레임워크 무관이므로 루트 문서를 그대로 따른다.

```
[ ] RA 산출물: 기능 요구사항이 FR-### 번호, 설명, 수용 기준(Acceptance Criteria), 우선순위(MoSCoW)를 포함하는가?
[ ] RA 산출물: 유스케이스가 UC-### 번호, Actor, 선행 조건, 주요 흐름, 예외 흐름, 후행 조건을 포함하는가?
[ ] SD 산출물: 서브도메인 분류표가 유형(Core/Supporting/Generic)과 구현 전략을 포함하는가?
[ ] SD 산출물: Bounded Context 정의서와 Context Map(관계 유형 + 선택 이유)이 있는가?
[ ] DM 산출물: 이벤트 스토밍 매핑 테이블이 Actor/Command/Aggregate/Domain Event/Policy 열을 포함하는가?
[ ] DM 산출물: 유비쿼터스 언어 용어 사전이 용어/정의/소속 Context를 포함하는가?
[ ] DM 산출물: Aggregate별 도메인 모델 구조가 Root/VO 목록/불변식(INV-###)을 포함하는가?
[ ] TD 산출물: 패키지 구조 트리가 domain/application/infrastructure/interfaces 4레이어를 포함하는가? (interfaces 복수형 — 이 저장소 관례)
[ ] TD 산출물: Aggregate 설계서가 protected constructor()/companion object 팩토리/private set 프로퍼티/불변식을 명시하는가?
[ ] TD 산출물: Repository 인터페이스 정의서가 find<Noun>s/save<Noun>/delete<Noun> 메서드(Kotlin interface)를 포함하는가?
[ ] TD 산출물: Application Service 정의서가 @Transactional 범위와 실패 시 처리(sealed class 예외)를 포함하는가?
[ ] TD 산출물: Event 흐름도가 Outbox → 메시지 큐 → Consumer 경로를 포함하는가? (동기 in-process 이벤트 버스가 아님)
[ ] IM 산출물: Vertical Slicing(유스케이스 단위 구현)으로 진행하고 있는가?
```

---

## STEP 16 — 가이드 수정 작업인 경우

**관련 문서**: [development-process.md](../../../docs/development-process.md) (루트 공용) · [conventions.md](./conventions.md)

> 코드 작업이 아니라 이 가이드(`docs/`, `architecture/*.md`) 자체를 수정하는 경우에만 적용한다.

```
[ ] 새로 추가하거나 수정한 설명이 한글로 작성되어 있는가?
[ ] 새 규칙에 올바른 예시(Kotlin 코드)와 필요 시 잘못된 예시가 함께 작성되어 있는가?
[ ] 작성한 예시가 이 가이드의 다른 규칙(파일 네이밍, null-safety, 에러 처리 등)을 위반하지 않는가?
[ ] examples/의 현재 코드와 문서가 제시하는 "올바른 패턴"이 다르다면, 그 차이를 "알려진 갭"으로 명시했는가?
    → 이 저장소의 다른 architecture/*.md 문서들이 일관되게 채택한 서술 방식이다
[ ] harness.sh가 검사하는 규칙과 문서의 서술이 모순되지 않는가? (harness/harness.sh 소스 확인)
[ ] 가이드 변경 시 main 브랜치가 아닌 새 브랜치에서 PR을 생성하는가?
```

---

## 체크리스트 활용 방법

AI Agent는 작업 완료 후 다음 순서로 자기 검토를 수행한다:

1. **STEP 1~14를 순서대로** 점검한다.
2. 위반 항목 발견 시 **즉시 해당 파일을 수정**하고 체크한다.
3. 수정 후 **연관된 파일(같은 도메인의 다른 레이어, import 참조 등)에도 영향이 없는지** 확인한다.
4. 설계 단계 작업이었다면 **STEP 15**도 함께 점검한다.
5. 가이드 수정 작업이었다면 **STEP 16**도 함께 점검한다.
6. 가능하면 `./harness/harness.sh <projectRoot>`를 실행해 정적 검사 결과를 확인한다.
7. 모든 체크 완료 후 작업을 마무리한다.

> 체크리스트는 가이드의 규칙을 요약한 것이다.
> 항목의 의도가 불명확하다면 해당 문서를 참조한다:
> - STEP 1 파일 구조 및 네이밍 → [conventions.md](conventions.md) 1~3절, [directory-structure.md](architecture/directory-structure.md)
> - STEP 2 Domain 레이어 → [tactical-ddd.md](architecture/tactical-ddd.md), [domain-service.md](../../../docs/architecture/domain-service.md) (루트 공용), [aggregate-id.md](architecture/aggregate-id.md)
> - STEP 3 레이어 아키텍처 / CQRS / 이벤트 → [layer-architecture.md](architecture/layer-architecture.md), [cqrs-pattern.md](architecture/cqrs-pattern.md), [domain-events.md](architecture/domain-events.md), [cross-domain.md](architecture/cross-domain.md)
> - STEP 4 Repository 패턴 → [repository-pattern.md](architecture/repository-pattern.md), [persistence.md](architecture/persistence.md)
> - STEP 5 Spring DI → [module-pattern.md](architecture/module-pattern.md), [shared-modules.md](architecture/shared-modules.md)
> - STEP 6 Kotlin 타이핑 → [conventions.md](conventions.md) 4절
> - STEP 7 에러 처리 → [error-handling.md](architecture/error-handling.md)
> - STEP 8 REST API 엔드포인트 → [conventions.md](conventions.md) 5절, [authentication.md](architecture/authentication.md), [rate-limiting.md](architecture/rate-limiting.md)
> - STEP 9 API 문서화 → [conventions.md](conventions.md) 8절
> - STEP 10 import → [conventions.md](conventions.md) 7절
> - STEP 11 스테레오타입 / 횡단 관심사 → [cross-cutting-concerns.md](architecture/cross-cutting-concerns.md), [scheduling.md](architecture/scheduling.md)
> - STEP 12 DB/인프라 → [persistence.md](architecture/persistence.md), [config.md](architecture/config.md), [container.md](architecture/container.md)
> - STEP 13 테스트 패턴 → [testing.md](architecture/testing.md)
> - STEP 14 전체 일관성 → 전체 문서 참조
> - STEP 15 설계 산출물 형태 → [development-process.md](../../../docs/development-process.md) (루트 공용)
> - STEP 16 가이드 수정 → [harness/harness.sh](../harness/harness.sh)

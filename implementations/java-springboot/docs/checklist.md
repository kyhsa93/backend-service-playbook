# AI Agent 자기 검토 체크리스트 (Java Spring Boot)

작업 완료 후, 아래 체크리스트를 순서대로 검토한다.
각 항목을 점검하여 위반이 발견되면 즉시 수정한 뒤 다음 항목으로 넘어간다.

### 검증 수행 규칙

- 각 STEP을 검증할 때 반드시 해당 파일을 Read 도구로 읽고 실제 코드와 대조한다.
- 코드를 읽지 않고 통과 처리하는 것은 금지한다.
- 위반 발견 시 즉시 수정한 후 다음 STEP으로 넘어간다.
- `examples/`는 이 저장소의 "알려진 gap"(문서화 패스 범위 밖으로 남겨둔 현재 코드)을 그대로 갖고 있다. 체크리스트는 `examples/`의 현재 패턴이 아니라 `docs/architecture/`가 정의하는 올바른 패턴을 기준으로 판단한다 — 새로 작성하는 코드가 `examples/`의 gap을 답습하고 있다면 위반으로 처리한다.

---

## STEP 1 — 파일 구조 및 네이밍

**관련 문서**: [conventions.md](./conventions.md) · [architecture/directory-structure.md](./architecture/directory-structure.md)

```
[ ] 파일명이 PascalCase가 아니고 public 클래스명과 일치하지 않는 것이 있는가?
    → 파일명 = public 클래스명으로 변경 (예: AccountRepository.java)
[ ] 패키지명에 대문자·하이픈·언더스코어가 섞여 있는가?
    → 모두 소문자, 구분자 없이 이어 쓴다 (예: com.example.accountservice.account.domain)
[ ] 최상위 인터페이스 레이어 패키지명이 interface(단수)로 되어 있는가?
    → Java 예약어 충돌을 피하기 위해 interfaces(복수형)를 사용해야 한다
[ ] Domain 레이어 파일명이 규칙을 따르는가?
    → Aggregate Root: <Aggregate>.java, 하위 Entity: <Entity>.java, Value Object: <Concept>.java(record), Domain Event: <DomainEvent>.java(record, 과거형)
[ ] Repository 인터페이스 파일명이 <Aggregate>Repository.java 형식이고 domain/에 있는가?
[ ] Repository 구현체 파일명이 <Aggregate>RepositoryImpl.java 형식이고 infrastructure/persistence/에 있는가?
[ ] Spring Data JPA 인터페이스 파일명이 <Aggregate>JpaRepository.java 형식이고 infrastructure/persistence/에 있는가?
[ ] Command Service 파일명이 <Verb><Noun>Service.java 형식이고 application/command/에 있는가?
[ ] Query Service 파일명이 Get<Noun>Service.java 형식이고 application/query/에 있는가?
[ ] Command/Result record 파일명이 <Verb><Noun>Command.java / <Verb><Noun>Result.java 형식인가?
[ ] Domain Event Handler 파일명이 <Domain>NotificationListener 등 역할이 드러나는 이름이고 application/event/에 있는가?
[ ] Technical Service 인터페이스 파일명이 <Concern>Service.java 형식이고 application/service/에 있는가?
[ ] Technical Service 구현체 파일명이 <Concern>ServiceImpl.java 형식이고 infrastructure/에 있는가?
[ ] Adapter 인터페이스 파일명이 <ExternalDomain>Adapter.java 형식이고 application/adapter/에 있는가?
[ ] Adapter 구현체 파일명이 <ExternalDomain>AdapterImpl.java 형식이고 infrastructure/에 있는가?
[ ] HTTP Controller 파일명이 <Domain>Controller.java 형식이고 interfaces/rest/에 있는가?
[ ] Interface DTO 파일명이 <Verb><Noun>Request.java / 응답은 Application Result를 그대로 반환(별도 Response 클래스 불필요)하는가?
[ ] 클래스명이 네이밍 규칙을 따르는가?
    → Aggregate Root: 도메인 명사 (Account) · Value Object: 개념명 (Money) · Domain Event: 과거형 (MoneyDepositedEvent)
    → Repository 인터페이스: <Aggregate>Repository / 구현체: <Aggregate>RepositoryImpl
    → Command: <Verb><Noun>Command / Result: <Verb><Noun>Result
    → 예외: <Domain>Exception, 내부 ErrorCode enum은 SCREAMING_SNAKE_CASE
[ ] 상수가 UPPER_SNAKE_CASE인가? 메서드·필드가 camelCase인가? Enum 상수가 UPPER_SNAKE_CASE인가?
```

---

## STEP 2 — Domain 레이어

**관련 문서**: [architecture/layer-architecture.md](./architecture/layer-architecture.md) · [architecture/tactical-ddd.md](./architecture/tactical-ddd.md) · [architecture/aggregate-id.md](./architecture/aggregate-id.md)

```
[ ] domain/ 패키지에 Aggregate Root, 하위 Entity, Value Object, Domain Event, Repository 인터페이스가 있는가?
[ ] Aggregate Root에 비즈니스 규칙과 불변식이 캡슐화되어 있는가?
    → Application Service에 상태 변경 조건 검사·계산 로직이 있다면 Aggregate 도메인 메서드로 이동
[ ] Aggregate Root가 정적 팩토리 메서드(static create())로만 생성되는가?
    → public 생성자를 열어두지 않고 protected <Aggregate>() {}로 막는다 (JPA가 요구하는 기본 생성자만 남긴다)
[ ] 하위 Entity의 생성 메서드가 public이 아닌 package-private static인가?
    → Aggregate Root를 거치지 않은 직접 생성을 컴파일 타임에 차단해야 한다
[ ] Value Object가 record + compact canonical constructor로 정의되어 검증이 항상 실행되는가?
    → 필드를 직접 대입하는 별도 생성자를 추가로 열어두지 않는다
[ ] Value Object의 연산 메서드(add, subtract 등)가 새 인스턴스를 반환하는가? (필드 직접 변경 금지)
[ ] Domain Event가 과거형 이름의 불변 record인가?
[ ] Domain 레이어 파일에 Spring 어노테이션(@Service, @Component, @Repository, @Controller, @RestController)이 있는가?
    → 있다면 제거. Domain은 프레임워크 무의존이 원칙이다
[ ] Domain 레이어 파일이 @Entity/@Embeddable/@Column 등 JPA 애노테이션을 갖고 있는가?
    → 이 저장소는 JPA 엔티티가 도메인을 겸하는 것을 "알려진 gap"으로 이미 명시하고 있다 (layer-architecture.md 참고) — 새 Aggregate도 같은 패턴(JPA 겸용)을 따르는 것은 즉시 위반은 아니다. 다만 이 사실을 문서/PR에서 숨기지 않고, Aggregate가 복잡해지면(다형성, 여러 하위 Entity 컬렉션) domain/AccountEntity 분리를 검토해야 한다
[ ] Repository 인터페이스가 domain/에 위치한 plain Java interface인가? (abstract class 아님)
[ ] Aggregate 간 직접 참조 없이 ID(String) 참조만 사용하는가?
    → 다른 Aggregate/BC를 필드 타입으로 직접 갖지 않는다 (ownerId만 갖고 User 객체를 갖지 않는 것처럼)
[ ] Aggregate 외부에서 setter 등으로 내부 상태를 직접 변경하는 코드가 있는가?
    → 있다면 Aggregate Root의 도메인 메서드를 통해 변경하도록 수정
[ ] Aggregate 생성 시 ID가 IdGenerator(32자리 hex, 하이픈 제거)로 생성되는가?
    → UUID.randomUUID().toString()을 그대로 accountId에 대입하지 않는다 (하이픈 포함은 위반, aggregate-id.md 참고)
[ ] DB 복원 시 ID를 새로 발급하지 않고 저장된 값을 그대로 사용하는가?
[ ] JPA @Id 대리키(Long, auto-increment)와 도메인 식별자(String, 32자리 hex)가 분리되어 있는가?
    → auto-increment 값을 API 응답/이벤트/외부 참조에 노출하지 않는다
[ ] Domain Event가 @Transient 필드에 수집되고 pullDomainEvents()로 꺼낸 뒤 비워지는가?
```

---

## STEP 3 — 레이어 아키텍처 / Application 서비스

**관련 문서**: [architecture/layer-architecture.md](./architecture/layer-architecture.md) · [architecture/cqrs-pattern.md](./architecture/cqrs-pattern.md)

```
[ ] Controller가 Command/Query 변환 + Service 호출 외에 다른 로직을 수행하는가?
    → 있다면 Application Service로 이동
[ ] Application Service(Command/Query)가 비즈니스 로직을 직접 수행하는가? (상태 변경 조건 검사, 계산 등)
    → 있다면 Aggregate의 도메인 메서드로 이동
[ ] Application Service가 HTTP 관련 타입(HttpStatus, ResponseEntity 등)을 참조하는가?
    → 있다면 제거. HTTP 변환은 Interfaces 레이어(@ExceptionHandler)에서만 수행
[ ] 쓰기 유스케이스가 application/command/에, 읽기 유스케이스가 application/query/에 있는가?
[ ] Command Service에 @Service + @RequiredArgsConstructor가 있는가? (@Transactional은 없어야 한다 — 트랜잭션 경계는 Repository.save()로 이관됨, STEP 8 참고)
[ ] Query Service에 @Service + @RequiredArgsConstructor + @Transactional(readOnly = true)가 있는가?
[ ] Query Service가 쓰기용 Repository(<Aggregate>Repository)를 직접 사용하는가?
    → cqrs-pattern.md가 명시하는 알려진 gap이다. 새로 작성하는 도메인은 이 gap을 반복하지 말고 별도 <Aggregate>Query 인터페이스(application/query/)를 도입해 Query Service가 이 인터페이스만 사용하도록 한다
[ ] <Aggregate>Query 인터페이스 구현체가 infrastructure/persistence/에 위치하고 Aggregate 전체 로딩 없이 projection 쿼리로 응답 스키마에 맞춰 조회하는가?
[ ] 필드 주입(@Autowired private X x)을 사용한 클래스가 있는가?
    → 있다면 생성자 주입(Lombok @RequiredArgsConstructor)으로 교체
[ ] 레이어 의존 방향이 올바른가? (interfaces → application → domain ← infrastructure)
    → 하위 레이어가 상위 레이어를 import하는 코드가 있다면 수정
[ ] Interface DTO(record)가 Application Command/Result 필드를 그대로 옮겨 담는 한 줄 변환 이상의 로직을 갖는가?
    → 있다면 그 로직을 Application 레이어로 이동
[ ] Repository의 save() 메서드가 Aggregate와 하위 Entity(pendingTransactions 등)를 함께 저장하는가?
    → Application Service가 하위 Entity를 별도로 저장하지 않는다
```

---

## STEP 4 — Repository 패턴

**관련 문서**: [architecture/repository-pattern.md](./architecture/repository-pattern.md)

```
[ ] Repository가 Aggregate Root 단위로 정의되어 있는가? (테이블/하위 Entity 단위로 별도 Repository를 만들지 않는다)
[ ] Repository 인터페이스가 domain/에 위치한 plain interface인가?
[ ] Repository 구현체가 infrastructure/persistence/에 위치하고 @Repository + @RequiredArgsConstructor를 갖는가?
[ ] Repository 조회 메서드명이 find<Noun>(단건)/find<Noun>s 또는 findAll(목록) 패턴을 따르는가?
[ ] Repository 저장 메서드명이 save<Noun>() 또는 save()인가?
[ ] Repository에 delete<Noun>() 메서드가 있고 soft delete(deletedAt 설정)로 구현되어 있는가?
    → repository-pattern.md/persistence.md가 명시하는 알려진 gap이다. 계좌 "종료"(상태 전이)와 "삭제"(deletedAt 설정)를 혼동하지 않는다 — 새 Aggregate는 둘 다 구현한다
[ ] Repository에 update<Noun>() 메서드가 있는가?
    → 있다면 제거. 조회 후 Aggregate 도메인 메서드로 상태를 바꾸고 save()로 저장한다
[ ] Repository 구현체가 Spring Data <Aggregate>JpaRepository(파생 쿼리)와 EntityManager(동적 JPQL)를 적절히 구분해 사용하는가?
    → 고정된 조건은 JpaRepository 파생 쿼리, 동적 필터는 EntityManager로 조립
[ ] 동적 필터 조립 시 값이 있을 때만 조건을 추가하는가? (null/blank/empty 체크 후 AND 추가)
[ ] Repository 구현체가 DB 레코드를 그대로 반환하지 않고 도메인 Aggregate로 다루는가?
    → 이 저장소는 JPA 엔티티가 도메인을 겸하므로 별도 매핑 코드 없이 Account 자체가 반환되는 것은 위반이 아니다(알려진 gap, STEP 2 참고) — 다만 Query 경로는 Aggregate 전체가 아니라 projection 결과(Result record)를 반환해야 한다
[ ] Repository 조회 메서드가 조회 시 deletedAt IS NULL 조건을 기본 적용하는가?
[ ] Aggregate가 하위 Entity를 pendingTransactions 같은 @Transient 컬렉션에 모으고, Repository.save()가 이를 함께 저장하는가?
```

---

## STEP 5 — Spring DI 컨테이너 / 패키지 경계

**관련 문서**: [architecture/module-pattern.md](./architecture/module-pattern.md) · [architecture/shared-modules.md](./architecture/shared-modules.md) · [architecture/cross-domain.md](./architecture/cross-domain.md)

```
[ ] 생성자 주입만 사용하는가? (Lombok @RequiredArgsConstructor + final 필드)
    → @Autowired 필드 주입이 있다면 제거
[ ] 스테레오타입 애노테이션이 실제 레이어와 일치하는가?
    → @Service는 application/에만, @Repository는 infrastructure/에만, @RestController는 interfaces/rest/에만
[ ] 서드파티 타입(AWS SDK 클라이언트 등)을 @Component로 직접 등록하려 했는가?
    → @Configuration 클래스의 @Bean 팩토리 메서드로 등록한다
[ ] 최상위 패키지가 레이어 기준(controllers/, services/)이 아니라 도메인(Bounded Context) 기준으로 나뉘어 있는가?
[ ] 하나의 도메인 패키지 안에 domain/application/infrastructure/interfaces 4개 서브패키지가 모두 있는가?
[ ] 다른 도메인(BC)의 Service/Repository를 Application Service가 직접 주입받고 있는가?
    → 있다면 Adapter 패턴으로 변경: application/adapter/에 인터페이스, infrastructure/에 구현체
[ ] Adapter 인터페이스가 호출하는 쪽(자기 BC)의 application/adapter/에 정의되어 있는가? (호출받는 쪽이 아니다)
[ ] Adapter가 다른 BC의 쓰기 메서드를 호출하는가?
    → 금지. Adapter는 조회(ACL)만 한다. 상태 변경이 필요하면 Integration Event로 전환
[ ] 순환 의존을 @Lazy로 우회하고 있는가?
    → 있다면 제거. Bounded Context 경계 재설계 또는 Adapter/이벤트 기반 통신으로 전환한다. @Lazy는 순환 의존의 근본 해결책이 아니다
[ ] 환경별 조건부 빈 등록에 @Profile을 사용하는가? (하드코딩된 if (env.equals("prod")) 분기 대신)
```

---

## STEP 6 — 에러 처리

**관련 문서**: [architecture/error-handling.md](./architecture/error-handling.md)

```
[ ] Domain 메서드가 불변식 위반 시 타입화된 예외(<Domain>Exception)를 즉시 throw하는가?
    → free-form 문자열이나 checked exception을 던지지 않는다
[ ] <Domain>Exception이 ErrorCode enum(SCREAMING_SNAKE_CASE)과 message를 함께 갖는가?
[ ] Application 레이어가 예외를 catch하지 않고 그대로 전파하는가?
    → Command/Query Service에서 try-catch로 삼키지 않는다
[ ] HTTP 예외 변환이 Interfaces 레이어(@ExceptionHandler 또는 @RestControllerAdvice)에서만 이루어지는가?
    → Domain/Application에서 HttpStatus/ResponseEntity를 참조하는가? 있다면 제거
[ ] 에러 응답 body가 { statusCode, code, message, error } 4개 필드 형식을 따르는가?
    → 이 저장소의 ErrorResponse는 이미 4필드(statusCode, code, message, error)를 갖는다. 새로 작성하는 코드도 ErrorResponse.of(status, code, message)로 이 형식을 따른다
[ ] 도메인이 2개 이상인 경우 MethodArgumentNotValidException 등 도메인 무관 예외를 @RestControllerAdvice 전역 클래스에서 처리하는가?
    → 각 Controller에 중복 정의하지 않는다
[ ] Validation 실패 응답의 code가 VALIDATION_FAILED 고정값인가?
[ ] @ExceptionHandler가 예외를 응답으로 변환하기 전에 로깅(log.warn/error)하는가?
```

---

## STEP 7 — REST API 엔드포인트

**관련 문서**: [conventions.md](./conventions.md) · [architecture/api-response.md](./architecture/api-response.md) · [architecture/authentication.md](./architecture/authentication.md)

```
[ ] URL이 동사가 아닌 복수 명사 리소스로 구성되어 있는가?
    → 올바른 예: GET /accounts, POST /accounts · 잘못된 예: GET /getAccounts, POST /createAccount
[ ] 리소스명이 복수형인가?
[ ] URL이 kebab-case 소문자만 사용하는가?
[ ] HTTP 메서드가 올바르게 사용되는가? (GET 조회, POST 생성, PUT 전체수정, PATCH 부분수정, DELETE 삭제)
[ ] 비 CRUD 행위가 하위 리소스 경로로 표현되는가? (예: POST /accounts/:accountId/suspend)
[ ] 응답 코드가 HTTP 메서드/@ResponseStatus에 맞는가? (POST 201, DELETE/상태전이형 POST 204, GET 200)
[ ] 페이지네이션 파라미터가 page(0부터)/take로 되어 있고 @RequestParam(defaultValue = ...)로 받는가?
[ ] 목록 조회 응답의 키 이름이 도메인 객체명 복수형인가? (예: transactions, count)
    → { data: [...] }, { result: [...] } 같은 범용 키 금지
[ ] 단건 조회 응답을 범용 래퍼({ success, data })로 감싸지 않고 Result record를 그대로 반환하는가?
[ ] 인증되지 않은 값(@RequestHeader("X-User-Id") 등)을 신뢰하고 있는가?
    → 이 저장소는 X-User-Id 헤더를 그대로 신뢰하는 알려진 gap이 있다(authentication.md 참고). Spring Security를 도입한 도메인이라면 @AuthenticationPrincipal/Authentication에서 userId를 꺼내야 한다
[ ] URL에 후행 슬래시나 파일 확장자가 없는가?
```

---

## STEP 8 — 트랜잭션 / Domain Event

**관련 문서**: [architecture/persistence.md](./architecture/persistence.md) · [architecture/domain-events.md](./architecture/domain-events.md) · [architecture/scheduling.md](./architecture/scheduling.md)

```
[ ] Command Service 메서드에 @Transactional이 없는가? (트랜잭션 경계는 Repository.save() 쪽에 있어야 한다 — Command Service에 있으면 회귀)
[ ] Query Service 메서드에 @Transactional(readOnly = true)가 있는가?
[ ] Repository.save()에 @Transactional이 있고, 여러 Repository에 걸친 쓰기가 하나의 @Transactional 메서드 안에서 이루어지는가?
[ ] 원본 트랜잭션과 격리해야 하는 부가 작업(알림 발송 등)에 Propagation.REQUIRES_NEW를 사용하는가?
    → 부가 작업의 실패가 원본 트랜잭션을 rollback-only로 오염시키지 않아야 한다
[ ] Domain Event가 Aggregate 내부 도메인 메서드에서만 생성되는가?
    → Command Service가 직접 이벤트를 생성하지 않는다
[ ] Command Service가 ApplicationEventPublisher.publishEvent()를 직접 호출하지 않는가?
    → 호출한다면 회귀다. Repository.save() 내부에서 Outbox 테이블에 이벤트를 저장하고, Command Service는 저장 직후 OutboxRelay.processPending()만 호출한다 — domain-events.md 참고
[ ] Repository.save()가 Aggregate 저장과 Outbox 저장을 하나의 @Transactional 안에서 원자적으로 처리하는가?
[ ] OutboxRelay가 outbox/ 패키지에 있고, Command Service가 저장 직후 동기 호출하는가? (`@Scheduled` 폴링이 아니다 — domain-events.md 참고)
[ ] @EnableScheduling이 @SpringBootApplication 클래스에 선언되어 있는가?
    → 없으면 @Scheduled가 조용히 무효화된다
[ ] @Scheduled 메서드가 예외를 명시적으로 로깅하는가? (조용히 삼켜지지 않도록)
[ ] 이벤트 핸들러(EventListener/Consumer)가 멱등하게 구현되어 있는가? (at-least-once 전달 대비)
[ ] 다중 인스턴스 배포를 고려해 FIFO dedup 또는 ShedLock으로 스케줄러 중복 실행을 방지하는가?
```

---

## STEP 9 — 인증 / 횡단 관심사

**관련 문서**: [architecture/authentication.md](./architecture/authentication.md) · [architecture/cross-cutting-concerns.md](./architecture/cross-cutting-concerns.md) · [architecture/rate-limiting.md](./architecture/rate-limiting.md)

```
[ ] 인증이 Interfaces 레이어(SecurityFilterChain/Controller)에서만 처리되는가?
    → Application Service가 토큰/Authorization 헤더를 직접 파싱하는가? 있다면 제거
[ ] authorizeHttpRequests가 필터 체인 전역에 적용되고 화이트리스트(permitAll())만 예외로 관리하는가?
[ ] JWT payload에 userId(subject)만 담고 role/email 등 가변·민감 정보를 담지 않는가?
[ ] Correlation ID가 Filter(OncePerRequestFilter, @Order(HIGHEST_PRECEDENCE))에서 주입되고 MDC에 저장되는가?
[ ] MDC 값을 try-finally로 반드시 정리하는가? (스레드 풀 재사용 시 값 누수 방지)
[ ] 요청 로깅이 HandlerInterceptor로 구현되어 있는가? (Filter와 역할을 혼동하지 않는다)
[ ] Domain 레이어에서 Logger/MDC/Spring Security 등 횡단 관심사를 사용하고 있지 않은가?
[ ] Rate Limiting을 도입했다면 인증보다 먼저 실행되는 Filter로 적용되어 있는가?
    → 이 저장소는 `RateLimitFilter`(전역, `/actuator/**` 제외)와 `@RateLimiter` 애노테이션(엔드포인트별) 이중 방어로 이미 적용 완료되었다(rate-limiting.md 참고)
[ ] Rate Limiting 초과 응답이 429 + 4필드 에러 형식을 따르는가? (도입한 경우)
[ ] 헬스체크/Actuator 엔드포인트가 인증·rate limiting에서 제외되어 있는가?
```

---

## STEP 10 — 설정 / 인프라

**관련 문서**: [architecture/config.md](./architecture/config.md) · [architecture/secret-manager.md](./architecture/secret-manager.md) · [architecture/container.md](./architecture/container.md) · [architecture/graceful-shutdown.md](./architecture/graceful-shutdown.md) · [architecture/local-dev.md](./architecture/local-dev.md) · [architecture/observability.md](./architecture/observability.md)

```
[ ] 설정값이 @Value로 여러 클래스에 흩어져 있는가?
    → 관심사별 @ConfigurationProperties record로 묶고 @Validated로 기동 시 검증한다
[ ] @ConfigurationProperties 바인딩 대상이 Infrastructure 레이어(@Configuration/@Component)로 한정되어 있는가?
    → Application Service/Domain이 설정값을 직접 참조하는가? 있다면 제거
[ ] 운영 프로필(application-prod.yml)에서 민감값 플레이스홀더가 기본값 없이(${VAR}) 선언되어 fail-fast를 강제하는가?
[ ] 민감값(DB 비밀번호, JWT secret, API 키)이 코드/설정 파일에 하드코딩되어 있지 않은가?
    → 운영 환경은 SecretService(AWS Secrets Manager) + TTL 캐시를 통해 조회한다
[ ] ddl-auto: update로 운영 스키마를 자동 동기화하고 있는가?
    → 알려진 gap이다. 마이그레이션 도구(Flyway)를 도입하고 ddl-auto: validate로 전환한다. 테스트 환경(Testcontainers)의 create-drop은 예외로 허용된다
[ ] Dockerfile이 멀티스테이지 Layered JAR 빌드를 사용하는가? (Build → Extract → Runtime 3단계)
[ ] 런타임 이미지가 JRE 기반인가? (JDK 아님)
[ ] ENTRYPOINT가 exec form(["java", "..."])인가? (shell form/gradlew 래퍼 금지)
[ ] 컨테이너가 non-root 사용자로 실행되는가?
[ ] server.shutdown: graceful + spring.lifecycle.timeout-per-shutdown-phase가 설정되어 있는가?
[ ] Actuator의 liveness/readiness 프로브가 활성화되어 있는가?
[ ] 로그가 구조화된 JSON 형태이고 필드명이 snake_case인가? (StructuredArguments.kv 사용)
[ ] Domain 레이어에서 로깅을 수행하지 않는가?
```

---

## STEP 11 — 테스트 패턴

**관련 문서**: [architecture/testing.md](./architecture/testing.md)

```
[ ] Domain 단위 테스트가 Spring 컨텍스트 없이 순수 Java로 작성되어 있는가? (new Account.create(...) 직접 호출)
[ ] Application 단위 테스트가 @ExtendWith(MockitoExtension.class) + @Mock으로 Repository/Query 인터페이스를 mock하는가?
    → 구체 클래스(<Aggregate>RepositoryImpl)를 mock하지 않는다
[ ] E2E 테스트가 @Testcontainers + @SpringBootTest(webEnvironment = RANDOM_PORT)로 실제 HTTP 요청을 검증하는가?
[ ] E2E 테스트가 in-memory DB가 아닌 Testcontainers 실제 컨테이너(Postgres 등)를 사용하는가?
[ ] Aggregate 불변식 위반 테스트(잘못된 입력 → <Domain>Exception + ErrorCode 검증)가 작성되어 있는가?
[ ] Domain Event 발행 여부를 pullDomainEvents()로 검증하는 테스트가 있는가?
[ ] 테스트 파일이 Gradle 표준 소스셋(src/test/java, 동일 패키지 미러링)에 배치되어 있는가?
[ ] 테스트 메서드명이 이 저장소의 관례(완전한 한글 문장, 예: 정지된_계좌에_입금하면_예외를_던진다())를 따르는가?
    → 영문 _when_..._then_ 패턴을 강제하지 않는다. 한글 문장 하나로 의도가 드러나면 된다
[ ] 예외 검증이 문자열 메시지가 아니라 ErrorCode enum 값으로 이루어지는가? (assertThatThrownBy + extracting)
```

---

## STEP 12 — 전체 일관성 최종 확인

**관련 문서**: [conventions.md](./conventions.md) · [architecture/design-principles.md](./architecture/design-principles.md)

```
[ ] 새로 추가한 클래스가 올바른 레이어 패키지(domain/application/infrastructure/interfaces)에 위치하는가?
[ ] 작업한 코드에서 TODO, System.out.println, 임시 주석이 남아있지 않은가?
[ ] 유비쿼터스 언어가 코드(클래스명, 메서드명, 변수명)에 일관되게 반영되어 있는가?
[ ] 주석 스타일이 // 인라인 주석만 사용하는가? (JSDoc류의 장황한 블록 주석 지양, Javadoc은 public API 설명 목적일 때만)
[ ] 로거 출력이 구조화된 형태인가? (snake_case 필드명, StructuredArguments.kv)
[ ] 커밋 메시지가 Conventional Commits 형식(feat/fix/refactor + scope)을 따르는가?
[ ] 커밋 메시지의 scope가 서비스 도메인명(account, notification 등)인가?
[ ] 커밋 메시지의 description이 한글·서술형이며 끝에 마침표가 없는가?
[ ] 브랜치명이 Conventional Branch 형식(<type>/<scope>-<description>)을 따르고 모든 단어가 kebab-case인가?
[ ] main 브랜치에 직접 commit/push하지 않고 PR을 통해 반영하는가?
[ ] PR 본문이 Summary + Test plan 형식을 따르는가?
[ ] 머지 전략이 Squash and merge인가?
[ ] ./harness.sh <projectRoot>를 실행해 FAIL 항목이 없는가?
    → FAIL 발견 시 harness 메시지가 안내하는 architecture/*.md 문서를 열어 수정한다
```

---

## STEP 13 — 설계 산출물 형태 (설계 단계 작업인 경우)

**관련 문서**: [development-process.md](../../../docs/development-process.md) (루트 공용) · [reference.md](./reference.md)

> 설계 단계(RA, SD, DM, TD) 산출물을 작성한 경우에만 적용한다. 프레임워크 무관 기준은 루트 [checklist.md](../../../docs/checklist.md) STEP 11과 동일하다 — 여기서는 Java Spring Boot 산출물에서 흔히 놓치는 지점만 보강한다.

```
[ ] RA 산출물: 기능 요구사항이 FR-### 번호, 설명, 수용 기준(Acceptance Criteria), 우선순위(MoSCoW)를 포함하는가?
[ ] RA 산출물: 유스케이스가 UC-### 번호, Actor, 선행 조건, 주요 흐름, 예외 흐름, 후행 조건을 포함하는가?
[ ] SD 산출물: 서브도메인 분류표가 유형(Core/Supporting/Generic)과 구현 전략을 포함하는가?
[ ] SD 산출물: Bounded Context 정의서가 책임·핵심 개념·소속 서브도메인을 포함하는가?
[ ] DM 산출물: 이벤트 스토밍 결과 매핑 테이블이 Actor/Command/Aggregate/Domain Event/Policy 열을 포함하는가?
[ ] DM 산출물: 유비쿼터스 언어 용어 사전이 용어(영문)/용어(한글)/정의/소속 Context를 포함하는가?
[ ] DM 산출물: Aggregate별 도메인 모델 구조가 Root/하위 Entity 목록/Value Object 목록/관계를 포함하는가?
[ ] DM 산출물: 비즈니스 규칙/불변식이 INV-### 번호와 위반 시 처리 방식(어떤 ErrorCode인지)을 포함하는가?
[ ] TD 산출물: 파일 구조 트리가 domain/application/infrastructure/interfaces 4레이어를 포함하는가? (interfaces 복수형 확인)
[ ] TD 산출물: Repository 인터페이스 정의서가 find/save/delete 메서드와 반환 타입을 명시하는가?
[ ] TD 산출물: Aggregate 설계서가 정적 팩토리 메서드/불변식/이벤트 목록/ID 생성 규칙(32자리 hex)을 포함하는가?
[ ] TD 산출물: DI 구성이 스테레오타입 애노테이션(@Service/@Repository/@Component)과 생성자 주입 방식을 명시하는가?
[ ] IM 산출물: Vertical Slicing(유스케이스 단위 구현)으로 진행하고 있는가?
```

---

## STEP 14 — 가이드 수정 작업인 경우

**관련 문서**: [development-process.md](../../../docs/development-process.md) (루트 공용) · [conventions.md](./conventions.md)

> 코드 작업이 아니라 이 가이드(`docs/`) 자체를 수정하는 경우에만 적용한다.

```
[ ] 새로 추가하거나 수정한 설명이 한글로 작성되어 있는가?
[ ] 새 규칙에 실제 코드 예시(올바른 방식/잘못된 방식)가 함께 작성되어 있는가?
[ ] 예시가 architecture/*.md가 실제로 문서화한 패턴과 모순되지 않는가?
    → 모순이 있다면 architecture/ 문서를 먼저 확인하고 예시를 맞춘다
[ ] examples/의 알려진 gap을 마치 올바른 패턴인 것처럼 예시로 삼지 않았는가?
[ ] 가이드 변경 시 main 브랜치가 아닌 새 브랜치에서 PR을 생성하는가?
```

---

## 체크리스트 활용 방법

AI Agent는 작업 완료 후 다음 순서로 자기 검토를 수행한다:

1. **STEP 1~12를 순서대로** 점검한다.
2. 위반 항목 발견 시 **즉시 해당 파일을 수정**하고 체크한다.
3. 수정 후 **연관된 파일(같은 도메인 패키지 내 다른 클래스, DI 대상 등)에도 영향이 없는지** 확인한다.
4. 설계 단계 작업이었다면 **STEP 13**도 함께 점검한다.
5. 가이드 수정 작업이었다면 **STEP 14**도 함께 점검한다.
6. 모든 체크 완료 후 `./harness.sh <projectRoot>`로 구조 규칙을 최종 확인한다.

> 체크리스트는 가이드의 규칙을 요약한 것이다.
> 항목의 의도가 불명확하다면 해당 문서를 참조한다:
> - STEP 1 파일 구조 및 네이밍 → [conventions.md](conventions.md) 1~2절, [directory-structure.md](architecture/directory-structure.md)
> - STEP 2 Domain 레이어 → [tactical-ddd.md](architecture/tactical-ddd.md), [aggregate-id.md](architecture/aggregate-id.md)
> - STEP 3 레이어 아키텍처 → [layer-architecture.md](architecture/layer-architecture.md), [cqrs-pattern.md](architecture/cqrs-pattern.md)
> - STEP 4 Repository 패턴 → [repository-pattern.md](architecture/repository-pattern.md)
> - STEP 5 Spring DI → [module-pattern.md](architecture/module-pattern.md), [cross-domain.md](architecture/cross-domain.md)
> - STEP 6 에러 처리 → [error-handling.md](architecture/error-handling.md)
> - STEP 7 REST API → [conventions.md](conventions.md) 5절, [api-response.md](architecture/api-response.md)
> - STEP 8 트랜잭션/이벤트 → [persistence.md](architecture/persistence.md), [domain-events.md](architecture/domain-events.md)
> - STEP 9 인증/횡단 관심사 → [authentication.md](architecture/authentication.md), [cross-cutting-concerns.md](architecture/cross-cutting-concerns.md)
> - STEP 10 설정/인프라 → [config.md](architecture/config.md), [container.md](architecture/container.md)
> - STEP 11 테스트 패턴 → [testing.md](architecture/testing.md)
> - STEP 12 전체 일관성 → [conventions.md](conventions.md), harness/harness.sh
> - STEP 13 설계 산출물 형태 → [development-process.md](../../../docs/development-process.md) (루트 공용)
> - STEP 14 가이드 수정 → 이 저장소 CLAUDE.md의 가이드 관리 원칙

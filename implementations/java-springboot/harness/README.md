# Harness — Java Spring Boot 프로젝트 구조·어노테이션 규칙 검사

`docs/`(공통) + `docs/architecture/*.md`(Java 구현)의 가이드 규칙 중 **기계 검증 가능한 항목**을 외부 Java Spring Boot 프로젝트에 적용하는 정적 분석 도구. 설계 원칙은 루트 [`../../../docs/harness.md`](../../../docs/harness.md)를 따른다 — 아키텍처 규칙 준수만 평가하고, `examples/`의 Account 도메인 같은 특정 업무 도메인 지식을 전제로 삼지 않는다.

원래는 순수 bash+grep 스크립트였지만("설치 불필요"가 그때의 설계 가치), 이 저장소의 다른 harness(nestjs=TypeScript, go=Go, kotlin-springboot=Kotlin)처럼 **검사 대상과 같은 언어로 작성하는 편**이 관용적이라고 판단해 순수 Java 프로그램으로 재작성했다 — Gradle/Maven 같은 무거운 빌드 도구는 쓰지 않고 `javac` 직접 컴파일만 사용한다.

## 구조

```
harness/
  harness.sh                     컴파일(필요 시)+실행 래퍼
  src/
    Main.java                    CLI 엔트리 — 규칙 목록 정의 + 결과 집계/출력
    Kind.java                    PASS/FAIL/SKIP
    Finding.java                 규칙 검사 결과 한 건
    RuleResult.java              규칙 하나의 section + findings 목록
    Rule.java                    규칙 함수 시그니처(String -> RuleResult)
    JavaFiles.java                공통 헬퍼(collectJavaFiles, relTo, pathContains, readText)
    rules/
      FileNaming.java             규칙별 구현 파일 (규칙 하나당 파일 하나)
      RepositoryAnnotation.java
      ServiceAnnotation.java
      DomainPurity.java
      ControllerPlacement.java
      PackageStructure.java
      SharedInfra.java
      EventPlacement.java
      NoEventPublisherInCommand.java
      TransactionBoundary.java
      OutboxDrainOrder.java
      CqrsQueryPurity.java
      RepositoryNaming.java
      DomainLayerIsolation.java
      InterfaceNoInfrastructure.java
      AggregateNoPublicSetters.java
      NoCrossAggregateReference.java
      NoDirectEnvAccessOutsideConfig.java
      NoCrossBcRepositoryInApplication.java
      NoLoggingInDomain.java
      SchedulerInInfrastructureOnly.java
      NoSilentCatch.java
      DockerfileConventions.java
      AggregateIdFormat.java
      ErrorResponseSchema.java
      SoftDeleteFilter.java
      TypedErrorsOnly.java
      RateLimitWired.java
  test/
    RuleTest.java                 자체 fixture 테스트 러너(외부 프레임워크 의존성 없음)
    testdata/<rule>/good/         해당 규칙을 통과해야 하는 최소 fixture
    testdata/<rule>/bad-*/        해당 규칙을 위반해 실패해야 하는 fixture
  build/                          컴파일 산출물(.gitignore 대상, 커밋되지 않음)
```

각 규칙은 `harness.Rule`(`Function<String, RuleResult>`) 시그니처의 정적 메서드이며, `Main.java`의 `RULES` 목록에 등록된 순서대로 실행·출력된다.

## 사용

```bash
# 저장소 루트에서 — src/가 바뀌었을 때만 재컴파일하고 캐시된 클래스를 재사용한다
bash implementations/java-springboot/harness.sh <projectRoot>
```

`javac`/`java`가 PATH에 없다면 `JAVAC=/path/to/javac JAVA=/path/to/java bash harness.sh <projectRoot>`처럼 환경변수로 지정한다.

## 규칙 목록

| 이름 | 파일 | 역할 |
|------|------|------|
| `file-naming` | `FileNaming.java` | 파일명이 `PascalCase.java`인지 |
| `repository-annotation` | `RepositoryAnnotation.java` | `@Repository` → `infrastructure/` |
| `service-annotation` | `ServiceAnnotation.java` | `@Service` → `application/` |
| `domain-purity` | `DomainPurity.java` | `domain/`에 Spring 어노테이션(`@Service`/`@Component`/`@Repository`/`@Controller`/`@RestController`) 금지 |
| `controller-placement` | `ControllerPlacement.java` | `@RestController` → `interfaces/` |
| `package-structure` | `PackageStructure.java` | `domain/` 형제로 `application/{command,query}`, `infrastructure/`, `interfaces/` 존재 |
| `shared-infra` | `SharedInfra.java` | `OutboxWriter` 참조 시 `outbox/`에 `OutboxWriter.java`/`OutboxPoller.java`/`OutboxConsumer.java` 존재 확인, `*TaskQueue*` 참조 시 `task-queue/` 배치 확인 |
| `event-placement` | `EventPlacement.java` | `*EventHandler`/`@EventListener` → `application/event/`; `*IntegrationEvent`(`V1`/`V2`… 버전 접미사 허용) → `application/integrationevent/`; `outbox/` 안의 `*EventHandler`는 Outbox 디스패치 계약으로 예외 허용 |
| `no-event-publisher-in-command` | `NoEventPublisherInCommand.java` | Command Service가 `ApplicationEventPublisher`/`@EventListener`/`publishEvent()`를 쓰면 실패 — Outbox 경유해야 함 |
| `transaction-boundary` | `TransactionBoundary.java` | Command Service에 `@Transactional`이 없고, Outbox를 저장하는 `*RepositoryImpl`에는 있는지 확인 |
| `outbox-drain-order` | `OutboxDrainOrder.java` | Command Service(`application/command/`)가 `OutboxRelay`/`OutboxPoller`/`OutboxConsumer`를 직접 참조하거나 `processPending()`/`poll()`/`drainOnce()`를 호출하면 실패 — Outbox → 큐 발행/수신은 독립적으로 주기 실행되는 Poller/Consumer만의 책임이다(동기 드레인 금지, domain-events.md) |
| `cqrs-query-purity` | `CqrsQueryPurity.java` | `application/query/` 하위 파일(주석 제외)이 쓰기용 Repository 타입을 참조하면 실패 — Query Service는 별도 Query 인터페이스(`AccountQuery` 등)만 의존해야 함(cqrs-pattern.md). nestjs harness의 `cqrs-pattern` evaluator를 이식한 규칙 |
| `repository-naming` | `RepositoryNaming.java` | `domain/`·`application/query/` 하위 `*Repository`/`*Query` 인터페이스 메서드가 `findByXxx`류 파생 쿼리, bare `findAll`, `count`로 시작하는 메서드, bare `save`/`delete`(대상 명사 없는 형태), `update`로 시작하는 메서드(별도 update 메서드 자체가 금지) 블록리스트에 걸리면 실패 — `find<Noun>s`/`save<Noun>`/`delete<Noun>` 형태만 허용(repository-pattern.md). `infrastructure/`의 구현체·내부 Spring Data JPA 파생 쿼리 메서드는 검사 대상 아님 |
| `domain-layer-isolation` | `DomainLayerIsolation.java` | `<domain>/domain/` 파일이 자기 자신 또는 형제 도메인의 `application/`·`infrastructure/`·`interfaces/`를 import하면 실패 — import 문의 패키지 경로만 보는 구조적 검사라 특정 프레임워크 이름을 하드코딩하지 않는다(layer-architecture.md) |
| `interface-no-infrastructure` | `InterfaceNoInfrastructure.java` | `interfaces/`(REST Controller 등) 파일이 `infrastructure/`를 직접 import하면 실패 — `application/`(Command/Query Service)만 거쳐야 함(layer-architecture.md) |
| `aggregate-no-public-setters` | `AggregateNoPublicSetters.java` | `domain/`의 `class` 선언 파일에 JavaBean 스타일 `public void setX(...)` 또는 Lombok `@Setter`가 있으면 실패 — 상태 변경은 이름 있는 도메인 메서드로만(tactical-ddd.md). 현재 Aggregate는 이미 이 패턴을 안 쓰므로 회귀 가드 성격 |
| `no-cross-aggregate-reference` | `NoCrossAggregateReference.java` | `payment/domain/Payment.java`가 `Refund` 타입을, `payment/domain/Refund.java`가 `Payment` 타입을 필드/파라미터로 직접 참조하면 실패 — ID 문자열 참조만 허용(domain-service.md). 두 Aggregate가 공존하는 실제 사례(Payment BC)에 한정한 블록리스트 |
| `no-direct-env-access-outside-config` | `NoDirectEnvAccessOutsideConfig.java` | `domain/`·`application/`에서 `System.getenv(...)` 직접 호출 시 실패 — 환경 변수 접근은 `@ConfigurationProperties`로 감싸 `config/`(또는 `infrastructure/`)에서만(config.md) |
| `no-cross-bc-repository-in-application` | `NoCrossBcRepositoryInApplication.java` | 한 도메인의 `application/` 파일이 다른 도메인의 `domain/*Repository`·`application/query/*Query` 인터페이스를 직접 import하면 실패 — 크로스 도메인 읽기는 호출하는 쪽이 소유한 Adapter(ACL)를 거쳐야 함(cross-domain-communication.md) |
| `no-logging-in-domain` | `NoLoggingInDomain.java` | `domain/`에서 `org.slf4j`/`@Slf4j`/`LoggerFactory` 사용 시 실패 — Domain 레이어 로깅 금지(observability.md) |
| `scheduler-in-infrastructure-only` | `SchedulerInInfrastructureOnly.java` | `domain/`·`application/`에서 `@Scheduled`/`@EnableScheduling` 사용 시 실패 — Scheduler는 infrastructure/에 배치해야 함(scheduling.md). `outbox/OutboxPoller`(공용 인프라 패키지)나 부트스트랩 진입점의 `@EnableScheduling`처럼 domain/application 밖에 있는 정당한 사용은 통과 |
| `no-silent-catch` | `NoSilentCatch.java` | `application/`·`infrastructure/`에서 완전히 빈 `catch (...) {}` 블록이 있으면 실패 — 예외를 조용히 삼키지 않고 로깅 후 처리하거나 재throw해야 함(observability.md) |
| `dockerfile-conventions` | `DockerfileConventions.java` | `Dockerfile`이 멀티스테이지(`FROM` 2개 이상)인지, `HEALTHCHECK` 지시문이 있는지, `.dockerignore`가 존재하고 `.git`/빌드 산출물을 제외하는지 확인(container.md). `.java` 파일이 아니라 두 텍스트 파일 자체를 검사하는 유일한 규칙 |
| `aggregate-id-format` | `AggregateIdFormat.java` | `common/IdGenerator.java`가 `UUID.randomUUID().toString()`의 하이픈을 `.replace("-", "")`로 제거하는지 확인 — Aggregate ID는 32자리 hex, 36자 하이픈 포함 문자열이 아니어야 함(aggregate-id.md). ID 생성이 이 유틸리티 한 곳으로 모여 있어 단일 파일만 검사한다 |
| `error-response-schema` | `ErrorResponseSchema.java` | `common/web/GlobalExceptionHandler.java`(`@RestControllerAdvice`)의 `@ExceptionHandler` 메서드가 반환하는 `ResponseEntity<Xxx>`의 제네릭 타입을 동적으로 찾아, 그 타입이 정확히 `statusCode`(숫자)/`code`(String)/`message`(String 또는 배열)/`error`(String) 4필드만 갖는지 확인(error-handling.md). 필드명 하드코딩 없이 GlobalExceptionHandler가 실제로 반환하는 타입을 파싱한다 — `record`/일반 `class` 둘 다 인식 |
| `soft-delete-filter` | `SoftDeleteFilter.java` | `deletedAt` 컬럼을 가진 `*JpaEntity`를 조회하는 `*RepositoryImpl.java`의 find 메서드에 `deletedAt IS NULL`(또는 동일 의미) 필터가 있는지 확인(persistence.md) — 하드 삭제 금지. Entity에 `@SQLRestriction`/`@Where` 전역 필터가 있으면 RepositoryImpl 검사를 생략(둘 중 어느 메커니즘이든 인정). `deletedAt` 컬럼이 없는 Entity(아직 삭제 유스케이스가 없는 Card/Payment/Refund 등)는 검사 대상에서 자연히 제외 |
| `typed-errors-only` | `TypedErrorsOnly.java` | `domain/`·`application/`에서 `throw new RuntimeException(...)`/`IllegalStateException(...)`/`IllegalArgumentException(...)`/`UnsupportedOperationException(...)`/`Exception(...)`처럼 문자열과 함께 일반 예외를 직접 던지면 실패 — 도메인별 타입화 예외(`AccountException` + `ErrorCode` enum)만 허용(error-handling.md, AGENTS.md "에러는 enum으로 타입화"). 현재 코드에 이 패턴이 없어 순수 회귀 가드 |
| `rate-limit-wired` | `RateLimitWired.java` | `RateLimitFilter`가 `@Component`로 Spring bean 등록되어 있는지, `RateLimiterConfig.custom()`으로 제한 값을 하드코딩하지 않고 `RateLimiterRegistry`에서 named instance를 동적으로 조회하는지(#181 회귀 가드), `FilterRegistrationBean.setEnabled(false)`로 명시적으로 비활성화되지 않았는지 확인(rate-limiting.md). `interfaces/`의 `@RateLimiter` 애노테이션 사용도 관찰해 PASS로 기록하되, 없어도 실패로 잡지 않음(선택 사항) |

## 회귀 테스트

```bash
cd implementations/java-springboot/harness
export JAVA_HOME=/path/to/jdk PATH="$JAVA_HOME/bin:$PATH"   # 필요 시
javac -d build/classes $(find src -name "*.java")
javac -cp build/classes -d build/test-classes test/RuleTest.java
cd test && java -cp ../build/classes:../build/test-classes RuleTest
```

각 규칙은 최소 `test/testdata/<rule>/good/`(통과해야 함)와 `test/testdata/<rule>/bad-*/`(실패해야 함) fixture로 검증된다. 외부 테스트 프레임워크(JUnit 등)는 새로 끌어오지 않고, 실패 시 `AssertionError`를 던지는 자체 assert 메서드만으로 작성했다(nestjs harness의 `run-fixtures.ts`와 같은 취지).

새 규칙을 추가하거나 기존 규칙을 수정할 때는:
1. `src/rules/<Rule>.java`에 로직 구현(또는 수정) — `public static RuleResult check(String rootPath)` 시그니처
2. `test/testdata/<rule>/good/`, `test/testdata/<rule>/bad-*/` fixture 작성
3. `test/RuleTest.java`의 `TESTS` 목록에 케이스 추가(신규 규칙인 경우 `src/Main.java`의 `RULES`에도 등록)
4. 위 "회귀 테스트" 명령으로 확인

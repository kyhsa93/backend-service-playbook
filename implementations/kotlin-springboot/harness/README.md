# Harness — Kotlin Spring Boot 프로젝트 구조·어노테이션 규칙 검사

`docs/`(공통) + `docs/architecture/*.md`(Kotlin 구현)의 가이드 규칙 중 **기계 검증 가능한 항목**을 외부 Kotlin Spring Boot 프로젝트에 적용하는 정적 분석 도구. 설계 원칙은 루트 [`../../../docs/harness.md`](../../../docs/harness.md)를 따른다 — 아키텍처 규칙 준수만 평가하고, `examples/`의 Account 도메인 같은 특정 업무 도메인 지식을 전제로 삼지 않는다.

원래는 순수 bash+grep 스크립트였지만("설치 불필요"가 그때의 설계 가치), 이 저장소의 다른 harness(nestjs=TypeScript, go=Go)처럼 **검사 대상과 같은 언어로 작성하는 편**이 관용적이라고 판단해 순수 Kotlin 프로그램으로 재작성했다 — Gradle 같은 무거운 빌드 도구는 쓰지 않고 `kotlinc` 직접 컴파일만 사용한다.

## 구조

```
harness/
  harness.sh                     컴파일(필요 시)+실행 래퍼
  src/
    Main.kt                      CLI 엔트리 — 규칙 목록 정의 + 결과 집계/출력
    Rule.kt                      공통 타입(Kind, Finding, RuleResult, Rule)
    KtFiles.kt                   공통 헬퍼(collectKtFiles, relTo, pathContains, segmentBefore)
    rules/
      FileNaming.kt               규칙별 구현 파일 (규칙 하나당 파일 하나)
      RepositoryAnnotation.kt
      ServiceAnnotation.kt
      DomainPurity.kt
      ControllerPlacement.kt
      SealedException.kt
      PackageStructure.kt
      SharedInfra.kt
      EventPlacement.kt
      NoEventPublisherInCommand.kt
      TransactionBoundary.kt
      OutboxNoSyncDrain.kt
      CqrsPattern.kt
      NotificationE2eTest.kt
      RepositoryNaming.kt
      DomainLayerIsolation.kt
      InterfaceNoInfrastructure.kt
      AggregateNoPublicSetters.kt
      NoCrossAggregateReference.kt
      NoDirectEnvAccessOutsideConfig.kt
      NoCrossBcRepositoryInApplication.kt
      NoLoggingInDomain.kt
      SchedulerInInfrastructureOnly.kt
      NoSilentCatch.kt
      DockerfileConventions.kt
      AggregateIdFormat.kt
      ErrorResponseSchema.kt
      SoftDeleteFilter.kt
      TypedErrorsOnly.kt
      RateLimitWired.kt
  test/
    RuleTest.kt                   자체 fixture 테스트 러너(외부 프레임워크 의존성 없음)
    testdata/<rule>/good/         해당 규칙을 통과해야 하는 최소 fixture
    testdata/<rule>/bad-*/        해당 규칙을 위반해 실패해야 하는 fixture
  build/                          컴파일 산출물(.gitignore 대상, 커밋되지 않음)
```

각 규칙 함수는 `harness.Rule`(`(String) -> RuleResult`) 시그니처를 가지며, `Main.kt`의 `RULES` 목록에 등록된 순서대로 실행·출력된다.

## 사용

```bash
# 저장소 루트에서 — src/가 바뀌었을 때만 재컴파일하고 캐시된 jar를 재사용한다
bash implementations/kotlin-springboot/harness.sh <projectRoot>
```

`kotlinc`가 PATH에 없다면 `KOTLINC=/path/to/kotlinc bash harness.sh <projectRoot>`처럼 환경변수로 지정한다.

## 규칙 목록

| 이름 | 파일 | 역할 |
|------|------|------|
| `file-naming` | `FileNaming.kt` | 파일명이 `PascalCase.kt`인지 |
| `repository-annotation` | `RepositoryAnnotation.kt` | `@Repository` → `infrastructure/` |
| `service-annotation` | `ServiceAnnotation.kt` | `@Service` → `application/` |
| `domain-purity` | `DomainPurity.kt` | `domain/`에 Spring 어노테이션(`@Service`/`@Component`/`@Repository`/`@Controller`/`@RestController`) 금지 |
| `controller-placement` | `ControllerPlacement.kt` | `@RestController` → `interfaces/` |
| `sealed-exception` | `SealedException.kt` | `sealed class *Exception\|*Error` → `domain/` |
| `package-structure` | `PackageStructure.kt` | `domain/` 형제로 `application/{command,query}`, `infrastructure/`, `interfaces/` 존재 |
| `shared-infra` | `SharedInfra.kt` | `OutboxWriter` 참조 시 `outbox/`에 `OutboxWriter.kt`/`OutboxPoller.kt`/`OutboxConsumer.kt` 존재 확인, `*TaskQueue*` 참조 시 `task-queue/` 배치 확인 |
| `event-placement` | `EventPlacement.kt` | `*EventHandler`/`*IntegrationEvent`/`@EventListener` → `application/event/`(또는 integration-event) |
| `no-event-publisher-in-command` | `NoEventPublisherInCommand.kt` | Command Service가 `ApplicationEventPublisher`/`@EventListener`/`publishEvent()`를 쓰면 실패 — Outbox 경유해야 함 |
| `transaction-boundary` | `TransactionBoundary.kt` | Command Service에 `@Transactional`이 없고, Outbox를 저장하는 `*RepositoryImpl`에는 있는지 확인 |
| `outbox-no-sync-drain` | `OutboxNoSyncDrain.kt` | Command Service가 `OutboxRelay`/`OutboxPoller`/`OutboxConsumer`를 참조하거나 `processPending`/`poll`/`drainOnce`를 호출하면 실패 — Outbox → 큐 발행/수신은 독립적으로 주기 실행되는 Poller/Consumer만의 책임(동기 드레인 금지, domain-events.md) |
| `cqrs-pattern` | `CqrsPattern.kt` | `application/query/` 파일이 쓰기 모델 Repository(`*Repository`)에 의존하면 실패 — 읽기 전용 Query 인터페이스(`AccountQuery` 등)만 사용해야 함 (cqrs-pattern.md). 주석 안의 언급은 제외 |
| `notification-e2e-test` | `NotificationE2eTest.kt` | `NotificationE2ETest.kt` 존재 확인(다른 규칙은 `test/`를 검사 대상에서 제외하므로 이 회귀 테스트 삭제를 못 잡음) |
| `repository-naming` | `RepositoryNaming.kt` | `domain/`, `application/query/` 안의 `*Repository`/`*Query` 인터페이스 메서드가 `find<Noun>s`/`save<Noun>`/`delete<Noun>` 네이밍을 따르는지 확인(repository-pattern.md). `findBy...`, bare `findAll`/`count*`/`save`/`delete`/`update*`를 blocklist로 잡는다 — `infrastructure/`의 구현체·내부 Spring Data JPA 인터페이스(derived query 메서드)는 대상 아님 |
| `domain-layer-isolation` | `DomainLayerIsolation.kt` | `domain/` 파일이 (자신이 속한 도메인이든 형제 도메인이든) `application/`·`infrastructure/`·`interfaces/` 패키지를 import하면 실패(layer-architecture.md). 도메인 이름을 하드코딩하지 않는 경로 기반(`com.example.accountservice.<아무 도메인>.(application\|infrastructure\|interfaces)`) 구조적 검사 |
| `interface-no-infrastructure` | `InterfaceNoInfrastructure.kt` | `interfaces/`(REST 컨트롤러 등)가 `infrastructure/`를 직접 import하면 실패 — `application/`만 의존해야 함(layer-architecture.md) |
| `aggregate-no-public-setters` | `AggregateNoPublicSetters.kt` | `domain/`의 `class X private constructor()` 관용구(Aggregate/Entity)에서 `var` 프로퍼티가 `private set`이 아니면 실패(tactical-ddd.md "모든 프로퍼티가 `private set`"). `data class` Value Object는 애초에 `var`가 없어 대상 아님 |
| `no-cross-aggregate-reference` | `NoCrossAggregateReference.kt` | `payment/domain/`에서 `Payment`가 `Refund`를, `Refund`가 `Payment`를 필드로 직접 참조하면 실패 — ID 참조(`paymentId: String`)만 허용(domain-service.md `RefundEligibilityService` 예시). `evaluate(payment: Payment, refund: Refund)`처럼 Domain Service가 여러 Aggregate를 함수 파라미터로 받는 것은 대상 아님 |
| `no-direct-env-access-outside-config` | `NoDirectEnvAccessOutsideConfig.kt` | `domain/`, `application/`에서 `System.getenv(...)` 직접 호출 시 실패 — `config/`(`@ConfigurationProperties`)와 `infrastructure/`만 환경 변수에 접근 가능(config.md) |
| `no-cross-bc-repository-in-application` | `NoCrossBcRepositoryInApplication.kt` | `application/` 파일이 자신이 속한 도메인이 아닌 다른 도메인의 `domain/*Repository`, `*Query`를 직접 import하면 실패 — 크로스 도메인 조회는 Adapter(`application/adapter/` + `infrastructure/*AdapterImpl`)를 거쳐야 함(cross-domain-communication.md) |
| `no-logging-in-domain` | `NoLoggingInDomain.kt` | `domain/`에서 SLF4J/`kotlin-logging` 등 로깅 API 사용 시 실패(observability.md "Domain 레이어에서 로깅하지 않는다") |
| `scheduler-in-infrastructure-only` | `SchedulerInInfrastructureOnly.kt` | `@Scheduled`/`@EnableScheduling`이 `domain/` 또는 `application/`에 있으면 실패(scheduling.md). `outbox/`(공유 인프라)와 최상위 부트스트랩 클래스는 두 경로 밖이므로 통과 |
| `no-silent-catch` | `NoSilentCatch.kt` | `application/`, `infrastructure/`에서 완전히 빈 `catch (e: Exception) { }` 블록 발견 시 실패(observability.md) — 로깅·rethrow 등 내용이 하나라도 있으면 대상 아님(오탐 방지를 위한 좁은 blocklist) |
| `dockerfile-conventions` | `DockerfileConventions.kt` | `examples/Dockerfile`을 텍스트로 파싱해 멀티스테이지 빌드(`FROM` 2개 이상)·`HEALTHCHECK` 존재·`.dockerignore`의 빌드 산출물/`.git` 제외 패턴 존재를 확인(container.md) |
| `aggregate-id-format` | `AggregateIdFormat.kt` | 프로젝트 안의 `GenerateId.kt`(ID 생성 유틸)가 `UUID.randomUUID().toString()`을 하이픈 제거 없이 그대로 반환하지 않는지 확인 — 32자리 hex(하이픈 없음) 대신 하이픈 포함 UUID가 Aggregate ID로 쓰이는 것을 막는다(aggregate-id.md) |
| `error-response-schema` | `ErrorResponseSchema.kt` | `*ErrorResponse` data class가 정확히 `statusCode`(숫자)/`code`(String)/`message`(String 또는 배열)/`error`(String) 4필드만 갖는지 확인 — 필드명은 JSON 직렬화 이름과 그대로 매핑되므로 대소문자까지 정확히 일치해야 한다(error-handling.md) |
| `soft-delete-filter` | `SoftDeleteFilter.kt` | `deletedAt` soft-delete 컬럼을 가진 JPA Entity라면, 그 Entity를 조회하는 `*RepositoryImpl.kt`의 find 쿼리가 `deletedAt IS NULL`(또는 `findBy...DeletedAtIsNull` derived query, 또는 Entity의 `@SQLRestriction`/`@Where` 전역 필터)을 갖는지 확인 — hard delete 금지 원칙 위반(삭제된 행이 계속 조회됨)을 잡는다(persistence.md). `deletedAt` 컬럼이 아예 없는 Entity(삭제 유스케이스 자체가 없는 도메인)는 대상 아님 |
| `typed-errors-only` | `TypedErrorsOnly.kt` | `domain/`, `application/`에서 `throw RuntimeException("...")`/`throw IllegalStateException("...")` 같은 free-form 문자열을 가진 제네릭 예외 생성 시 실패 — `sealed class *Exception` 계층의 타입화된 하위 클래스만 throw해야 한다(root AGENTS.md "에러는 enum으로 타입화", error-handling.md). outbox/(infrastructure 성격) 등 domain/application 밖의 기술적 예외는 대상 아님 |
| `rate-limit-wired` | `RateLimitWired.kt` | Resilience4j `RateLimiter`를 참조하는 `OncePerRequestFilter` 클래스가 `acquirePermission()` 등 실제 제한 로직을 호출하고, `@Component` 등으로 스스로 빈 등록되거나 `FilterRegistrationBean`/`addFilterBefore`로 명시 등록되어 있는지 확인 — 둘 중 하나라도 없으면 정의만 되어 있고 요청 파이프라인에 실제로 적용되지 않는 죽은 코드다(rate-limiting.md) |

## 회귀 테스트

```bash
cd implementations/kotlin-springboot/harness
export JAVA_HOME=/path/to/jdk PATH="$JAVA_HOME/bin:$PATH"   # 필요 시
kotlinc src test/RuleTest.kt -include-runtime -d build/test.jar
cd test && java -cp ../build/test.jar harness.test.RuleTestKt
```

각 규칙은 최소 `test/testdata/<rule>/good/`(통과해야 함)와 `test/testdata/<rule>/bad-*/`(실패해야 함) fixture로 검증된다. 외부 테스트 프레임워크(JUnit 등)는 새로 끌어오지 않고, 실패 시 `AssertionError`를 던지는 자체 assert 함수만으로 작성했다(nestjs harness의 `run-fixtures.ts`와 같은 취지).

새 규칙을 추가하거나 기존 규칙을 수정할 때는:
1. `src/rules/<Rule>.kt`에 로직 구현(또는 수정) — `fun check<Rule>(rootPath: String): RuleResult` 시그니처
2. `test/testdata/<rule>/good/`, `test/testdata/<rule>/bad-*/` fixture 작성
3. `test/RuleTest.kt`의 `TESTS` 목록에 케이스 추가(신규 규칙인 경우 `src/Main.kt`의 `RULES`에도 등록)
4. 위 "회귀 테스트" 명령으로 확인

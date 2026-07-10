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
    KtFiles.kt                   공통 헬퍼(collectKtFiles, relTo, pathContains)
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
      OutboxDrainOrder.kt
      NotificationE2eTest.kt
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
| `shared-infra` | `SharedInfra.kt` | `OutboxRelay` 참조 시 `outbox/`에 `OutboxWriter.kt`/`OutboxRelay.kt` 존재 확인, `*TaskQueue*` 참조 시 `task-queue/` 배치 확인 |
| `event-placement` | `EventPlacement.kt` | `*EventHandler`/`*IntegrationEvent`/`@EventListener` → `application/event/`(또는 integration-event) |
| `no-event-publisher-in-command` | `NoEventPublisherInCommand.kt` | Command Service가 `ApplicationEventPublisher`/`@EventListener`/`publishEvent()`를 쓰면 실패 — Outbox 경유해야 함 |
| `transaction-boundary` | `TransactionBoundary.kt` | Command Service에 `@Transactional`이 없고, Outbox를 저장하는 `*RepositoryImpl`에는 있는지 확인 |
| `outbox-drain-order` | `OutboxDrainOrder.kt` | `OutboxRelay`를 참조하는 Command Service가 `save(...)` 호출 뒤에 `processPending(...)`을 호출하는지(순서 포함) — domain-events.md의 핵심 불변식 |
| `notification-e2e-test` | `NotificationE2eTest.kt` | `NotificationE2ETest.kt` 존재 확인(다른 규칙은 `test/`를 검사 대상에서 제외하므로 이 회귀 테스트 삭제를 못 잡음) |

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

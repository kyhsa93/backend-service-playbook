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
| `shared-infra` | `SharedInfra.java` | `OutboxRelay` 참조 시 `outbox/`에 `OutboxWriter.java`/`OutboxRelay.java` 존재 확인, `*TaskQueue*` 참조 시 `task-queue/` 배치 확인 |
| `event-placement` | `EventPlacement.java` | `*EventHandler`/`*IntegrationEvent`/`@EventListener` → `application/event/`(또는 integration-event); `outbox/` 안의 `*EventHandler`는 Outbox 디스패치 계약으로 예외 허용 |
| `no-event-publisher-in-command` | `NoEventPublisherInCommand.java` | Command Service가 `ApplicationEventPublisher`/`@EventListener`/`publishEvent()`를 쓰면 실패 — Outbox 경유해야 함 |
| `transaction-boundary` | `TransactionBoundary.java` | Command Service에 `@Transactional`이 없고, Outbox를 저장하는 `*RepositoryImpl`에는 있는지 확인 |
| `outbox-drain-order` | `OutboxDrainOrder.java` | `OutboxRelay`를 참조하는 Command Service가 `save(...)` 호출 뒤에 `processPending(...)`을 호출하는지(순서 포함) — domain-events.md의 핵심 불변식 |

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

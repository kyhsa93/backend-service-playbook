# 핵심 설계 원칙 요약 (Spring Boot)

이 저장소의 Java Spring Boot 구현이 따르는 가장 중요한 규칙을 압축한 TL;DR 인덱스다. 각 항목의 상세 근거와 예시 코드는 연결된 문서를 참고한다 — 여기서 새로운 규칙을 만들지 않는다.

1. **도메인 기준 패키지 구조** — `<domain>/{domain,application,infrastructure,interfaces}/` 4개 서브패키지로 나눈다. `interfaces`는 복수형이다(Java 예약어 `interface` 회피). ([directory-structure.md](directory-structure.md))

2. **Domain 레이어는 프레임워크 무의존이어야 한다** — `Account`/`Transaction`/`Money`(domain)는 `jakarta.persistence.*`를 import하지 않는 순수 객체다. JPA 매핑은 `AccountJpaEntity`/`TransactionJpaEntity`/`MoneyEmbeddable`(infrastructure)이 전담하고, `AccountMapper`/`TransactionMapper`가 변환한다. ([layer-architecture.md](layer-architecture.md))

3. **Aggregate는 정적 팩토리로만 생성한다** — `public` 생성자를 열지 않고 `protected Account() {}` + `static Account create(...)`. 하위 Entity(`Transaction`)는 `static` **package-private** 생성으로 Aggregate Root를 거치지 않은 직접 생성을 컴파일 타임에 차단한다. ([tactical-ddd.md](tactical-ddd.md))

4. **Value Object는 `record` + compact canonical constructor로 표현한다** — 생성 시 항상 검증이 실행되고, 필드가 `final`이라 연산은 항상 새 인스턴스를 반환한다(`Money.add()`). ([tactical-ddd.md](tactical-ddd.md))

5. **Repository는 Aggregate Root 단위로만 둔다** — 인터페이스는 `domain/`, 구현체(`<Aggregate>RepositoryImpl`)는 `infrastructure/persistence/`. Spring Data `JpaRepository`(`<Aggregate>JpaRepository`)는 구현체 내부에서만 사용하고 Domain/Application에 노출하지 않는다. ([repository-pattern.md](repository-pattern.md))

6. **의존성 주입은 생성자 주입만 사용한다 — `@Autowired` 필드 주입 금지** — Lombok `@RequiredArgsConstructor`로 `final` 필드 생성자를 생성한다. 생성자가 하나뿐인 클래스는 Spring이 `@Autowired` 없이도 자동 주입한다. `examples/`의 모든 Service/Repository/Controller가 이 패턴을 따른다. ([module-pattern.md](module-pattern.md))

7. **`@Transactional`은 Application 레이어(Command/Query Service)에만 붙인다** — Domain과 Infrastructure에는 붙이지 않는다. 쓰기는 `@Transactional`, 읽기 전용은 `@Transactional(readOnly = true)`로 구분해 Hibernate dirty checking 오버헤드를 줄인다. 트랜잭션 경계를 원본과 격리해야 하는 부가 작업(알림 발송 등)은 `Propagation.REQUIRES_NEW`로 분리한다. ([persistence.md](persistence.md), [layer-architecture.md](layer-architecture.md))

8. **Command/Query Service는 분리하되, Query는 별도 조회 인터페이스를 써야 한다** — `GetAccountService`/`GetTransactionsService` 모두 쓰기용 `AccountRepository`가 아니라 좁은 `AccountQuery`(application/query)에 의존한다. harness의 `cqrs-query-purity` 규칙이 `application/query/` 하위 파일의 Repository 직접 참조를 자동으로 잡아낸다. ([cqrs-pattern.md](cqrs-pattern.md))

9. **에러는 항상 타입화된 예외 + enum `ErrorCode`로 던진다** — free-form 문자열 금지. `AccountException(ErrorCode, message)`가 Domain 메서드 내부에서 즉시 throw되고, `@ExceptionHandler`가 Interface 레이어(Controller 또는 `@RestControllerAdvice`)에서만 HTTP 응답으로 변환한다. Domain/Application에서 `HttpStatus`/`ResponseEntity`를 참조하지 않는다. ([error-handling.md](error-handling.md))

10. **에러 응답은 4필드(`statusCode`/`code`/`message`/`error`)로 구성한다** — `ErrorResponse`가 이 4필드를 모두 가져, 클라이언트가 응답 바디만으로 HTTP 상태를 재확인할 수 있다. ([error-handling.md](error-handling.md))

11. **Interface DTO는 Application 객체의 thin wrapper다** — `record` 필드를 그대로 옮겨 담는 한 줄 변환(`new DepositCommand(accountId, requesterId, request.amount())`)으로 충분하다. MapStruct 같은 매핑 라이브러리는 필드 수가 많아지기 전까지 불필요하다. ([layer-architecture.md](layer-architecture.md))

12. **설정 접근은 Infrastructure 레이어로 한정한다** — `@Value`/`@ConfigurationProperties` 주입 대상은 `@Configuration`/`@Component` 클래스로 한정하고, Application Service와 Domain은 설정값을 직접 참조하지 않는다. `@ConfigurationProperties` + `@Validated`로 기동 시 fail-fast 검증을 한다 — `AwsProperties`/`SesProperties`로 이미 적용됨. ([config.md](config.md))

13. **크로스 BC 호출은 Adapter(동기) 아니면 Integration Event(비동기)뿐이다** — 다른 BC의 Service/Repository를 Application 레이어에서 직접 주입하지 않는다. 조회는 `application/adapter/`의 인터페이스 + `infrastructure/`의 구현체로, 상태 변경은 Outbox 기반 Integration Event로만 전파한다. ([cross-domain.md](cross-domain.md), [cross-domain-communication.md](../../../../docs/architecture/cross-domain-communication.md))

---

## 이 저장소의 "알려진 gap" — 원칙과 코드가 아직 불일치하는 항목

문서(`docs/architecture/`)는 위 13개 원칙을 이미 반영하고, `examples/`의 실제 코드도 대부분 이를 따른다 — 다음 항목만 아직 남은 gap이다.

| 원칙 (위 번호) | 코드 상태 |
|---|---|
| 12. Fail-fast 설정 검증 | 적용됨 — `AwsProperties`/`SesProperties`(`@ConfigurationProperties` + `@Validated`), 단 `accessKeyId`/`secretAccessKey`는 Bean Validation 대상이 아니고 `application-prod.yml`의 기본값 생략으로 fail-fast를 얻는다([config.md](config.md) 참고) |

전체 gap 목록은 [docs/implementations/java-springboot.md](../../../../docs/implementations/java-springboot.md)의 커버리지 표를 참고한다.

---

### 관련 문서

이 문서는 요약 인덱스다. 각 원칙의 전체 근거, 코드 예시, 트레이드오프 설명은 위에 링크된 개별 문서에 있다.

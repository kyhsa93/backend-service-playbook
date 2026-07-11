# 핵심 설계 원칙 요약 — Kotlin Spring Boot

이 저장소의 다른 21개 문서에서 이미 다루는 내용을 조합·재확인하기 위한 것이다. 각 항목은 어딘가 다른 문서에 상세 근거가 있다 — 여기서 새 규칙을 만들지 않는다.

1. **도메인 우선 디렉토리 구조** — `<domain>/{domain,application,infrastructure,interfaces}/` 4레이어. 레이어가 아니라 Bounded Context가 최상위 패키지 기준이다 ([directory-structure.md](directory-structure.md)).

2. **Domain 레이어는 프레임워크 무의존 (Spring·JPA 모두)** — `@Service`/`@Component`/`@Repository`/`@Controller`뿐 아니라 `jakarta.persistence`(`@Entity`/`@Embeddable`/`@Column` 등)도 domain/에서 금지한다. JPA 매핑은 `infrastructure/persistence/`의 `AccountJpaEntity`/`TransactionJpaEntity`/`MoneyEmbeddable` + `AccountMapper`/`TransactionMapper`가 전담한다. harness의 `domain-purity` 검사가 둘 다(Spring 스테레오타입 + domain/의 `jakarta.persistence` import) 강제한다 ([directory-structure.md](directory-structure.md), [layer-architecture.md](layer-architecture.md)).

3. **Aggregate 생성은 `protected constructor()` + `companion object.create()` 팩토리로 통제** — 외부 코드가 `Account()`로 빈 인스턴스를 만들 수 없고, 유일한 공개 생성 경로가 불변식을 검증한다 ([tactical-ddd.md](tactical-ddd.md)).

4. **모든 Aggregate/Entity 프로퍼티는 `private set`** — 상태 변경은 도메인 메서드(`deposit()`, `suspend()` 등)를 통해서만 이루어진다. 컴파일러가 "외부에서 직접 대입 불가"를 강제한다 ([tactical-ddd.md](tactical-ddd.md)).

5. **Value Object와 이벤트는 `data class`** — `equals()`/`hashCode()`/`copy()`를 컴파일러가 생성한다. 불변식은 `init {}` 블록에서 즉시 검증한다 (`Money`, [tactical-ddd.md](tactical-ddd.md)).

6. **"찾지 못함"은 `Optional<T>`이 아니라 `T?`** — 널 가능 타입 + `?:` 엘비스 연산자로 표현한다. 컴파일러가 null 체크 누락을 막아 도메인 불변식("조회 성공 이후에는 반드시 값이 존재한다")을 타입으로 강제한다 ([layer-architecture.md](layer-architecture.md), [repository-pattern.md](repository-pattern.md)).

7. **에러는 `sealed class` 계층으로 타입화** — 같은 파일 내 상속만 허용되므로 컴파일러가 하위 타입 전체를 알고, `when` 분기의 완전성(exhaustiveness)을 컴파일 타임에 검사한다. free-form 문자열 예외는 금지 (`AccountException`, [error-handling.md](error-handling.md)).

8. **생성자 주입, `@Autowired` 불필요** — 주 생성자(primary constructor) 파라미터가 그대로 DI 대상이다. 생성자가 하나뿐이면 Spring이 자동 인식한다 ([layer-architecture.md](layer-architecture.md), [module-pattern.md](module-pattern.md)).

9. **Repository/Adapter는 `interface`가 곧 DI 토큰** — Java/TypeScript처럼 `abstract class`나 별도 바인딩 문법이 필요 없다. Spring이 클래스패스의 유일한 구현체를 자동 주입한다 ([repository-pattern.md](repository-pattern.md), [cross-domain.md](cross-domain.md)).

10. **Repository 조회는 `find<Noun>s` 하나로 통일, 단건은 `take: 1` + `.firstOrNull()`** — 전용 단건 조회 메서드(`findByAccountIdAndOwnerId` 같은)를 늘리지 않는다. 현재 코드는 이 원칙과 어긋나 있음이 문서에 명시된 갭이다 ([repository-pattern.md](repository-pattern.md)).

11. **Repository에 update 메서드를 두지 않는다** — 상태 변경은 Aggregate 도메인 메서드로 수행한 뒤 `save<Noun>`으로 영속화한다 ([repository-pattern.md](repository-pattern.md)).

12. **Command/Query Service 분리, Query는 `@Transactional(readOnly = true)`** — Hibernate가 dirty checking을 생략해 읽기 성능을 최적화한다 ([cqrs-pattern.md](cqrs-pattern.md), [layer-architecture.md](layer-architecture.md)).

13. **관심사별 설정은 `@ConfigurationProperties` + `data class`** — 기본값 없는 필드가 그 자체로 Fail-fast 검증이 된다. `@Value("${...}")`를 서비스 코드에 흩뿌리지 않는다 ([config.md](config.md)).

14. **설정 값은 Infrastructure 레이어에서만 주입** — Domain/Application Service 생성자에 `@ConfigurationProperties` 타입을 직접 주입하지 않는다 ([config.md](config.md)).

15. **Domain 레이어는 어떤 횡단 관심사(Filter/Interceptor/Logger)도 import하지 않는다** — 로깅·Correlation ID는 Application 레이어 이상에서만 다룬다 ([cross-cutting-concerns.md](cross-cutting-concerns.md)).

### 관련 문서

이 목록의 각 항목이 왜 그런지, 그리고 현재 `examples/`가 아직 반영하지 못한 갭이 무엇인지는 각 원칙 옆에 링크된 문서에서 상세히 다룬다. `examples/`의 실제 코드가 아니라 **문서가 정의하는 패턴을 새 코드 작성의 기준으로 삼는다** — 이는 `docs/implementations/kotlin-springboot.md`의 "알려진 갭" 표에도 반복해 명시되어 있다.

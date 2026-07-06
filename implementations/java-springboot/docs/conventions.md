# 코딩 컨벤션 (Java Spring Boot)

> 프레임워크 무관 원칙은 루트 [conventions.md](../../../docs/conventions.md) 참고. 이 문서는 Java/Spring 관용으로 다시 쓰였다 — Kotlin Spring Boot 구현과 Spring 메커니즘 자체는 겹치지만, 네이밍·타이핑 표현은 각 언어의 관용을 따른다([java-springboot/CLAUDE.md](../CLAUDE.md) 참고).

## 1. 파일 네이밍 규칙

- 모든 파일명: `PascalCase`, **파일명 = public 클래스명** (Java 컴파일러 요구사항)
- 패키지명: 소문자, 하이픈/언더스코어 없음 — `com.example.accountservice.account.domain`
- Aggregate Root: `<Aggregate>.java` (`domain/`) — 예: `Account.java`
- 하위 Entity: `<Entity>.java` (`domain/`) — 예: `Transaction.java`
- Value Object: `<Concept>.java`, `record` (`domain/`) — 예: `Money.java`
- Domain Event: `<DomainEvent>.java`, `record`, 과거형 (`domain/`) — 예: `MoneyDepositedEvent.java`
- Repository 인터페이스: `<Aggregate>Repository.java` (`domain/`)
- Repository 구현체: `<Aggregate>RepositoryImpl.java` (`infrastructure/persistence/`)
- Spring Data JPA 인터페이스: `<Aggregate>JpaRepository.java` (`infrastructure/persistence/`)
- Command Service: `<Verb><Noun>Service.java` (`application/command/`) — 예: `CreateAccountService.java`
- Query Service: `Get<Noun>Service.java` (`application/query/`) — 예: `GetAccountService.java`
- Query 인터페이스(도입 시): `<Aggregate>Query.java` (`application/query/`)
- Query 구현체(도입 시): `<Aggregate>QueryImpl.java` (`infrastructure/persistence/`)
- Command: `<Verb><Noun>Command.java`, `record` (`application/command/`)
- Result: `<Verb><Noun>Result.java`, `record` (`application/{command,query}/`)
- 예외: `<Domain>Exception.java` (`domain/`) — 내부에 `ErrorCode` enum을 중첩 정의
- Domain Event Handler: `<DomainEvent>Handler.java`, `outbox.OutboxEventHandler` 구현 (`application/event/`) — 예: `AccountCreatedEventHandler.java`
- Technical Service 인터페이스: `<Concern>Service.java` (`application/service/`) — 예: `NotificationService.java`
- Technical Service 구현체: `<Concern>ServiceImpl.java` (`infrastructure/`)
- Adapter 인터페이스: `<ExternalDomain>Adapter.java` (`application/adapter/`)
- Adapter 구현체: `<ExternalDomain>AdapterImpl.java` (`infrastructure/`)
- HTTP Controller: `<Domain>Controller.java` (`interfaces/rest/`)
- Interface DTO(요청): `<Verb><Noun>Request.java`, `record` (`interfaces/rest/`) — 예: `DepositRequest.java`
- 에러 응답: `ErrorResponse.java`, `record` (`interfaces/rest/`)
- `@ConfigurationProperties` 클래스: `<Concern>Properties.java`, `record` (`config/`) — 예: `AwsProperties.java`
- `@Configuration` 설정 클래스: `<Concern>Config.java` (해당 도메인의 `infrastructure/` 또는 공용 `config/`) — 예: `SesConfig.java`
- 순수 유틸: `<Concern>.java` (`common/`, 프레임워크 무의존) — 예: `IdGenerator.java`

---

## 2. 클래스 네이밍 규칙

- Aggregate Root: 도메인 명사 — `Account`
- 하위 Entity: 도메인 명사 — `Transaction`
- Value Object: 개념명 — `Money`
- 상태 enum: `<Domain>Status` — `AccountStatus`
- Domain Event: 과거형 — `MoneyDepositedEvent`, `AccountClosedEvent`
- Repository 인터페이스: `AccountRepository` / 구현체: `AccountRepositoryImpl`
- Spring Data JPA 인터페이스: `AccountJpaRepository`
- Command Service: `CreateAccountService`, `DepositService`, `SuspendAccountService`
- Query Service: `GetAccountService`, `GetTransactionsService`
- Command: `DepositCommand`, `CreateAccountCommand`
- Result: `CreateAccountResult`, `GetAccountResult`, `TransactionResult`
- 예외: `AccountException` (내부 `ErrorCode` enum은 `SCREAMING_SNAKE_CASE` 상수 — `ACCOUNT_NOT_FOUND`)
- Domain Event Handler: `AccountCreatedEventHandler`, `MoneyDepositedEventHandler`
- Technical Service 인터페이스: `NotificationService`, `SecretService`
- Adapter 인터페이스: `UserAdapter` (외부 BC명 + `Adapter`)
- HTTP Controller: `AccountController`
- Interface DTO: `DepositRequest`, `CreateAccountRequest`
- 상수: `UPPER_SNAKE_CASE` — `MAX_TRANSACTIONS_PER_PAGE`
- 메서드·필드: `camelCase` — `getAccountId()`, `pullDomainEvents()`
- Enum 상수: `UPPER_SNAKE_CASE` — `AccountStatus.ACTIVE`

---

## 3. `interfaces`(복수형) — Java 예약어 회피

`interface`는 Java 언어 키워드이므로 패키지명으로 쓸 수 없다. 루트 문서의 `interface/`(단수)를 이 저장소는 `interfaces/`(복수형)로 표기한다 — go/kotlin-springboot도 각자의 언어 제약에 맞춰 조정하는 지점과 동일한 종류의 타협이다. 새 도메인 패키지를 만들 때 `interface/`로 잘못 쓰지 않도록 주의한다.

```
com.example.accountservice.account.interfaces.rest   // 올바른 방식
com.example.accountservice.account.interface.rest    // 컴파일 에러 — interface는 예약어
```

---

## 4. Enum / 예외 코드 배치 규칙

- **상태를 나타내는 enum은 domain/에 별도 파일로 정의한다** — `AccountStatus.java`, `TransactionType.java`. Aggregate 클래스 내부에 인라인 정의하지 않는다.
- **에러 코드는 `<Domain>Exception` 내부에 중첩 enum(`ErrorCode`)으로 정의한다** — root의 별도 `<Domain>ErrorCode.ts` 파일 분리 대신, Java는 예외 클래스와 코드가 한 파일에서 응집되는 편이 자연스럽다(예외를 던지는 지점에서 `AccountException.ErrorCode.INSUFFICIENT_BALANCE`로 항상 정적 참조된다 — free-form 문자열 코드가 원천적으로 불가능하다).

```java
// account/domain/AccountStatus.java — 별도 파일
public enum AccountStatus {
    ACTIVE, SUSPENDED, CLOSED
}

// account/domain/AccountException.java — 예외 + ErrorCode 중첩 정의
public class AccountException extends RuntimeException {

    public enum ErrorCode {
        ACCOUNT_NOT_FOUND,
        INVALID_AMOUNT,
        INSUFFICIENT_BALANCE,
        DEPOSIT_REQUIRES_ACTIVE_ACCOUNT
    }

    private final ErrorCode code;

    public AccountException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode code() { return code; }
}
```

- **`ErrorCode`의 모든 값에 대응하는 실제 throw 지점이 있어야 한다** — 정의만 해두고 쓰지 않는 코드를 남기지 않는다.

---

## 5. 타이핑 패턴

### Value Object / Command / Result — `record`

```java
// 올바른 방식 — record로 불변성 + equals/hashCode 자동 획득
public record Money(long amount, String currency) {
    public Money {   // compact canonical constructor — 모든 생성 경로에 검증 적용
        if (amount < 0) throw new AccountException(AccountException.ErrorCode.INVALID_MONEY_AMOUNT, "금액은 0 이상이어야 합니다.");
    }
}

public record DepositCommand(String accountId, String requesterId, long amount) {}
public record CreateAccountResult(String accountId, String ownerId, long balance, String currency) {}
```

```java
// 잘못된 방식 — 가변 필드를 갖는 일반 class로 Command/Result를 표현
public class DepositCommand {
    private String accountId;
    public void setAccountId(String accountId) { this.accountId = accountId; }   // setter 존재 — 불변성 위반
}
```

### Aggregate Root / 하위 Entity — 정적 팩토리 + `protected` 생성자

```java
// 올바른 방식
protected Account() {}   // JPA가 요구하는 기본 생성자만 열어둠

public static Account create(String ownerId, String email, String currency) {
    Account account = new Account();
    account.accountId = IdGenerator.generate();   // 32자리 hex, 하이픈 없음 — aggregate-id.md 참고
    account.balance = new Money(0, currency);
    account.status = AccountStatus.ACTIVE;
    return account;
}
```

```java
// 잘못된 방식 — public 생성자로 임의 상태의 Account를 직접 생성 가능
public Account(String accountId, Money balance, AccountStatus status) { ... }
```

### `Optional<T>` — 단건 조회의 부재 표현

```java
// 올바른 방식 — Repository/Query 단건 조회는 Optional로 부재를 표현
Optional<Account> findByAccountIdAndOwnerId(String accountId, String ownerId);

// 호출 측
Account account = accountRepository.findByAccountIdAndOwnerId(accountId, requesterId)
        .orElseThrow(() -> new AccountException(AccountException.ErrorCode.ACCOUNT_NOT_FOUND, "계좌를 찾을 수 없습니다."));
```

```java
// 잘못된 방식 — null을 그대로 반환해 호출부마다 null 체크를 반복시킴
Account findByAccountIdAndOwnerId(String accountId, String ownerId);   // null 반환 가능
```

### `var` 사용 범위 — 지역 변수에 한정

```java
// 허용 — 우변 타입이 명확한 지역 변수
var qb = accountRepo.createQueryBuilder("account");

// 금지 — 필드, 메서드 파라미터, 반환 타입에는 var를 쓰지 않는다 (Java는 애초에 불가능하지만 명시)
```

### Lombok 사용 범위

- **`@RequiredArgsConstructor`**: Application Service, Repository 구현체, Technical Service 구현체 등 생성자 주입이 필요한 모든 클래스에 사용한다.
- **Domain 레이어(Aggregate, Value Object, Domain Event)에는 Lombok을 쓰지 않는다** — `record`가 이미 불변성/`equals`/`toString`을 제공하고, Aggregate는 정적 팩토리·불변식 검증이 있는 특수 생성 로직이 필요해 `@AllArgsConstructor`/`@Data` 같은 범용 애노테이션이 오히려 불변식을 우회하는 생성자를 열어버릴 수 있다.
- **`@Getter`/`@Setter`를 Aggregate Root에 붙이지 않는다** — `@Setter`는 캡슐화를 깨고 도메인 메서드를 우회한 상태 변경을 허용한다. Getter가 필요하면 직접 작성하거나 `@Getter`만 선택적으로 사용한다.

```java
// 올바른 방식 — Application Service
@Service
@RequiredArgsConstructor
@Transactional
public class CreateAccountService {
    private final AccountRepository accountRepository;
}

// 금지 — Domain 레이어에 @Data/@Setter
@Entity
@Data   // 금지 — equals/hashCode/setter가 불변식을 우회할 수 있음
public class Account { ... }
```

---

## 6. REST API 엔드포인트 설계 규칙

### URL 구조 — 리소스 중심, 복수 명사

URL은 **행위(동사)가 아닌 리소스(명사)**를 나타낸다. HTTP 메서드가 행위를 표현한다.

```
// 올바른 방식
GET    /accounts                     계좌 목록 조회
GET    /accounts/{accountId}         계좌 단건 조회
POST   /accounts                     계좌 개설
POST   /accounts/{accountId}/deposit 입금 (비 CRUD 행위 — 하위 리소스 경로)

// 잘못된 방식
GET    /getAccounts        동사를 URL에 넣지 않는다
POST   /createAccount      동사를 URL에 넣지 않는다
GET    /account/{id}       단수형 사용 금지 — 항상 복수형
```

### HTTP 메서드와 응답 코드 — `@ResponseStatus`

| 메서드 | 용도 | 성공 코드 | Spring 표현 |
|--------|------|----------|-------------|
| `GET` | 리소스 조회 | 200 OK | 기본값 (별도 애노테이션 불필요) |
| `POST` (생성) | 리소스 생성 | 201 Created | `@ResponseStatus(HttpStatus.CREATED)` |
| `POST` (상태 전이) | 상태 변경, 응답 바디 없음 | 204 No Content | `@ResponseStatus(HttpStatus.NO_CONTENT)` |
| `PUT` | 리소스 전체 수정 | 200 OK | 기본값 |
| `PATCH` | 리소스 부분 수정 | 200 OK | 기본값 |
| `DELETE` | 리소스 삭제 | 204 No Content | `@ResponseStatus(HttpStatus.NO_CONTENT)` |

```java
// 올바른 방식
@PostMapping
@ResponseStatus(HttpStatus.CREATED)
public CreateAccountResult createAccount(@Valid @RequestBody CreateAccountRequest request) { ... }

@PostMapping("/{accountId}/suspend")
@ResponseStatus(HttpStatus.NO_CONTENT)
public void suspendAccount(@PathVariable String accountId) { ... }
```

### 비 CRUD 행위 — 하위 리소스 경로

```
POST /accounts/{accountId}/deposit     입금
POST /accounts/{accountId}/withdraw    출금
POST /accounts/{accountId}/suspend     계좌 정지
POST /accounts/{accountId}/reactivate  계좌 재개
POST /accounts/{accountId}/close       계좌 종료
```

### 목록 조회 — 페이지네이션

```java
@GetMapping("/{accountId}/transactions")
public GetTransactionsResult getTransactions(
        @PathVariable String accountId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int take
) { ... }
```

```
GET /accounts/{accountId}/transactions?page=0&take=20
```

- `page`: 0부터 시작. `take`: 페이지 크기. Spring MVC가 `@RequestParam(defaultValue = ...)`로 문자열 → 기본 타입 변환을 자동 처리한다 — 별도 파싱 코드가 필요 없다.
- 목록 응답의 키 이름은 도메인 객체명 복수형(`transactions`)이어야 한다 — `data`/`result`/`items` 같은 범용 키 금지.

### URL 네이밍 규칙

- **복수 명사**: `/accounts`, `/transactions` (단수형 금지)
- **kebab-case**: 다단어 리소스는 하이픈으로 구분 (예: `/payment-methods`)
- **소문자만**: `/Accounts` (X) → `/accounts` (O)
- **후행 슬래시 없음**, **파일 확장자 없음**

---

## 7. 메서드 네이밍 및 구성

### Controller 메서드

- `create`, `deposit`, `withdraw`, `suspend`, `reactivate`, `close`, `get`, `getTransactions` 등 행위가 드러나는 동사 사용
- 반환 타입은 Application Result record를 그대로 사용 (별도 Response 클래스로 감싸지 않는다 — `layer-architecture.md` "Interface DTO — thin wrapper" 참고)
- 로직 없이 Command/Query 변환 + Service 위임만 수행

```java
// 올바른 방식
@PostMapping("/{accountId}/deposit")
@ResponseStatus(HttpStatus.CREATED)
public TransactionResult deposit(
        @PathVariable String accountId,
        @RequestBody DepositRequest request
) {
    return depositService.deposit(new DepositCommand(accountId, requesterId, request.amount()));
}
```

### Application Service 메서드 구성 순서

1. `private final` 필드 (Lombok `@RequiredArgsConstructor`가 생성자 생성)
2. public 유스케이스 메서드
3. private 헬퍼 메서드 (있는 경우)

### Repository 메서드 네이밍

- 조회: `find<Noun>` / `find<Noun>s` / `findAll(<Query>)` — Spring Data 파생 쿼리 관례(`findByAccountIdAndOwnerId`)와 자연스럽게 결합된다.
- 저장: `save<Noun>` 또는 `save`
- 삭제: `delete<Noun>` — soft delete로 구현 (`repository-pattern.md` 참고)
- `update<Noun>` 메서드는 두지 않는다 — 조회 후 Aggregate 도메인 메서드로 상태를 바꾸고 `save`로 저장한다.

### Aggregate 도메인 메서드

- 동사형 — `deposit()`, `withdraw()`, `suspend()`, `reactivate()`, `close()`
- 반환 타입은 필요한 경우만 (하위 Entity 생성 결과를 반환하는 `deposit()` 등), 대부분은 `void`
- 불변식 위반 시 메서드 진입 초반에 즉시 예외를 던진다 (guard clause 스타일)

---

## 8. import / 패키지 구성 패턴

### import 그룹 순서 — 표준 Java 관례

```java
// 1. java.* / javax.* / jakarta.*
import java.time.LocalDateTime;
import java.util.List;
import jakarta.persistence.Entity;

// 2. 서드파티 (org.springframework.*, lombok.* 등)
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;

// 3. 프로젝트 내부 (com.example.accountservice.*)
import com.example.accountservice.account.domain.Account;
import com.example.accountservice.account.domain.AccountRepository;
```

- IDE(IntelliJ) 기본 import 정렬이 이 순서를 자동 적용한다 — 와일드카드 import(`import com.example.accountservice.account.application.command.*`)는 같은 패키지 내 다수 클래스를 한 번에 참조할 때만 예외적으로 허용한다(`AccountController`가 실제로 이 패턴을 쓴다).
- **상대 경로 개념 자체가 Java에는 없다** — 패키지 선언(`package com.example.accountservice.account.domain;`)과 완전한 import 경로만 존재하므로 root의 "상대경로 import 금지" 규칙은 Java에서는 자동으로 충족된다.

### 패키지 = Bounded Context 경계

```
com.example.accountservice/
  account/     ← domain/application/infrastructure/interfaces 4레이어 전부 포함
  notification/
  common/      ← 공유 유틸/부품 (shared-modules.md 참고)
  AccountServiceApplication.java
```

레이어(`controllers/`, `services/`) 기준으로 최상위 패키지를 나누지 않는다 — 도메인(BC) 기준으로 나눈다.

---

## 9. Swagger / OpenAPI 문서화 패턴 — springdoc-openapi (도입 시)

`build.gradle`에 `springdoc-openapi` 의존성이 아직 없다(`bootstrap.md` 참고) — 이 절은 도입 시 따를 패턴이며, 임의로 이미 구현된 것처럼 문서화하지 않는다.

```groovy
// build.gradle — 도입 시 추가
implementation 'org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0'
```

```java
// 올바른 방식 — record 필드에 springdoc 애노테이션
public record CreateAccountRequest(
        @Schema(description = "이메일", example = "owner@example.com") @NotBlank @Email String email,
        @Schema(description = "통화 코드 (ISO 4217)", example = "KRW") @NotBlank String currency
) {}
```

```java
// Controller 메서드 — @Operation으로 요약, @ApiResponse로 상태 코드 문서화
@Operation(summary = "계좌 개설")
@ApiResponse(responseCode = "201", description = "생성 성공")
@PostMapping
@ResponseStatus(HttpStatus.CREATED)
public CreateAccountResult createAccount(@Valid @RequestBody CreateAccountRequest request) { ... }
```

- **DTO Validation은 Bean Validation(`jakarta.validation.constraints.*`)**로 표현한다 — `@NotBlank`, `@Email`, `@Min`, `@Max` 등. NestJS의 `class-validator` 데코레이터에 대응한다.
- springdoc은 `@RestController`/`@RequestMapping`/`@Valid` 애노테이션을 리플렉션으로 읽어 `/v3/api-docs`, `/swagger-ui.html`을 **자동** 노출한다 — NestJS의 `SwaggerModule.createDocument()`처럼 `main()`에서 문서 객체를 조립할 필요가 없다(`bootstrap.md` 참고).
- Bearer 인증 스킴, 제목/버전 등 커스터마이징은 `@Configuration` 클래스의 `@Bean OpenAPI` 하나로 충분하다.

---

## 10. 로거 패턴

### 선언 — 클래스 필드로 고정, SLF4J

```java
private static final Logger log = LoggerFactory.getLogger(NotificationServiceImpl.class);
```

### 구조화된 로그 — `StructuredArguments.kv(...)`

```java
// 올바른 방식 — snake_case 필드명을 명시적으로 구성
import static net.logstash.logback.argument.StructuredArguments.kv;

log.info("이메일 발송됨", kv("account_id", accountId), kv("event_type", eventType), kv("ses_message_id", messageId));
```

```java
// 지양 — {} 플레이스홀더만으로는 로그 수집기가 필드를 개별 인덱싱할 수 없다
log.info("이메일 발송됨: accountId={}, eventType={}", accountId, eventType);
```

- 로컬 프로필은 사람이 읽기 쉬운 평문(`%d{HH:mm:ss} %-5level %logger{36} - %msg%n`), 운영 프로필은 JSON(Logstash 인코더)으로 `springProfile` 분기한다 (`observability.md` 참고).
- Correlation ID는 `Filter`가 MDC에 주입하고, JSON 인코더가 `includeMdcKeyName`으로 자동 포함한다 — 매 로그 호출마다 correlationId를 인자로 넘길 필요가 없다.

### 레이어별 로깅 기준

| 레이어 | 로깅 대상 |
|---|---|
| Domain | **금지** — 어떤 도메인 메서드도 Logger를 import하지 않는다 |
| Application | 비즈니스 이벤트, 외부 호출 결과 |
| Infrastructure | 외부 연동 실패/재시도 |
| Interfaces | 요청 에러(`@ExceptionHandler` 진입 시 `log.warn`) |

---

## 11. 주석 스타일

- 비즈니스 도메인 설명은 한글 인라인 주석(`//`)으로 작성한다.
- Javadoc(`/** ... */`)은 **다른 BC/팀이 호출하는 public API**(Adapter 인터페이스 등)에만 선택적으로 사용한다 — 내부 구현 세부사항에는 쓰지 않는다.
- 긴 메서드는 섹션 주석으로 논리적 구분을 표시한다.

```java
// 올바른 방식 — Adapter 인터페이스에는 Javadoc으로 계약을 설명
public interface UserAdapter {
    /**
     * accountId 소유자의 표시 이름을 조회한다.
     * User BC의 내부 구조는 이 메서드 시그니처 뒤에 완전히 숨겨진다.
     */
    Optional<UserSummary> findUser(String ownerId);
}

// 올바른 방식 — Service 메서드 내부는 // 섹션 주석
public CreateAccountResult create(CreateAccountCommand command) {
    // Aggregate 생성 (불변식은 생성자/팩토리에서 검증)
    Account account = Account.create(command.ownerId(), command.email(), command.currency());
    // 저장 (Outbox 포함, 같은 트랜잭션)
    accountRepository.save(account);
    return new CreateAccountResult(account.getAccountId(), account.getOwnerId(), account.getBalance().amount(), account.getBalance().currency());
}
```

---

## 12. 커밋 메시지 컨벤션

루트 [conventions.md](../../../docs/conventions.md) 2절과 동일한 [Conventional Commits](https://www.conventionalcommits.org/) 스펙을 따른다 — Java Spring Boot 구현이라고 해서 다른 스킴을 쓰지 않는다.

### 메시지 구조

```
<type>(<scope>): <description>

[optional body]

[optional footer(s)]
```

### type 목록

| type | 설명 | 예시 |
|------|------|------|
| `feat` | 새로운 기능 추가 | `feat(account): 계좌 정지 기능 추가` |
| `fix` | 버그 수정 | `fix(account): 잔액 부족 시에도 출금이 성공하는 문제 수정` |
| `refactor` | 기능 변경 없이 코드 구조 변경 | `refactor(account): 조회 로직을 AccountQuery로 분리` |
| `docs` | 문서만 변경 | `docs: aggregate-id 가이드 추가` |
| `test` | 테스트 추가 또는 수정 | `test(account): 계좌 정지 불변식 단위 테스트 추가` |
| `chore` | 빌드, CI, 의존성 등 코드 외적인 작업 | `chore(deps): springdoc-openapi 의존성 추가` |
| `style` | 코드 포맷팅, 동작에 영향 없는 변경 | `style: import 순서 정리` |
| `perf` | 성능 개선 | `perf(account): 거래 내역 조회 쿼리 projection 최적화` |

### scope / description 규칙

- scope는 **서비스 도메인명**(`account`, `notification` 등) 또는 코드 외적 변경 대상(`ci`, `deps`, `docker`)
- description은 한글 서술형("추가", "수정", "제거"), 끝에 마침표 없음

### BREAKING CHANGE

```
feat(account)!: 계좌 응답 스키마 변경

BREAKING CHANGE: GetAccountResult의 balance 필드가 balance.amount/balance.currency로 분리됨
```

---

## 13. 브랜치 및 PR 컨벤션

루트 [conventions.md](../../../docs/conventions.md) 3절과 동일한 Conventional Branch 스킴을 따른다.

```
<type>/<scope>-<short-description>
```

| type | 용도 | 예시 |
|------|------|------|
| `feat` | 새 기능 개발 | `feat/account-suspend` |
| `fix` | 버그 수정 | `fix/account-withdraw-balance-check` |
| `refactor` | 리팩터링 | `refactor/account-query-separation` |
| `docs` | 문서 변경 | `docs/aggregate-id-guide` |
| `test` | 테스트 추가/수정 | `test/account-invariant` |
| `chore` | 빌드, CI, 의존성 | `chore/gradle-springdoc` |

**규칙:**
- 모든 단어는 `kebab-case`로 작성한다.
- `main` 브랜치에서 분기하고, `main`에 직접 commit/push하지 않는다.

### PR 워크플로우

```
1. git checkout main && git pull origin main
2. git checkout -b <type>/<scope>-<short-description>
3. git commit -m "<type>(<scope>): <description>"
4. git push -u origin <branch-name>
5. gh pr create --base main --title "<type>(<scope>): <description>" --body "..."
```

### PR 본문

```markdown
## Summary
- 변경 사항을 1~3줄로 요약

## Test plan
- [ ] ./harness.sh <projectRoot> 통과
- [ ] Domain/Application 단위 테스트 통과
- [ ] E2E 테스트 통과 (Testcontainers)
```

### 머지 전략

- **Squash and merge**를 기본으로 사용한다. 머지 후 원격 브랜치는 자동 삭제한다.

---

## 14. 테스트 패턴

**상세 근거와 코드 예시는 [architecture/testing.md](architecture/testing.md) 참고.** 여기서는 컨벤션 요약만 다룬다.

### 3계층 — Domain / Application / E2E

| 계층 | 프레임워크 | mock 대상 |
|---|---|---|
| Domain | 없음 (`new Account.create(...)` 직접 호출) | 없음 |
| Application | JUnit 5 + Mockito (`@ExtendWith(MockitoExtension.class)`) | Repository/Query **인터페이스** |
| E2E | `@SpringBootTest` + Testcontainers | 없음 (실제 컨테이너) |

### 테스트 파일 배치 — Gradle 표준 소스셋

```
src/test/java/com/example/accountservice/account/
  domain/AccountTest.java
  application/command/CreateAccountServiceTest.java
  interfaces/rest/AccountControllerE2ETest.java
```

NestJS처럼 소스 파일 옆에 `.spec.ts`를 두는 것이 아니라, `src/main`과 분리된 `src/test` 트리 안에서 동일한 패키지 구조를 미러링한다.

### 테스트 메서드 네이밍 — 완전한 한글 문장 (이 저장소의 확립된 관례)

```java
// 올바른 방식 — 이미 채택된 관례
@Test
void 정지된_계좌에_입금하면_예외를_던진다() { ... }

@Test
void 생성_요청이_유효하면_201과_계좌_정보를_반환한다() { ... }
```

Java 메서드명은 밑줄과 한글을 그대로 쓸 수 있다(유니코드 식별자 허용). root의 `<행위>_when_<조건>_then_<기대결과>` 영문 스네이크 케이스 패턴을 강제로 옮기지 않고, 완전한 한글 문장으로 테스트 의도를 표현하는 이 저장소의 관례를 그대로 따른다. `@DisplayName` 없이도 메서드명 자체가 읽힌다.

### 예외 검증 — 문자열이 아닌 `ErrorCode`로

```java
// 올바른 방식
assertThatThrownBy(() -> account.withdraw(2000))
        .isInstanceOf(AccountException.class)
        .extracting(e -> ((AccountException) e).code())
        .isEqualTo(AccountException.ErrorCode.INSUFFICIENT_BALANCE);
```

```java
// 잘못된 방식 — 메시지 문구가 바뀌면 테스트가 깨짐
assertThatThrownBy(() -> account.withdraw(2000)).hasMessage("잔액이 부족합니다.");
```

### E2E — Testcontainers 전용, in-memory DB 금지

```java
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountControllerE2ETest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
    }
}
```

H2 같은 in-memory DB로 대체하지 않는다 — 운영 DB(Postgres)와 SQL 방언 차이로 인한 거짓 양성/음성을 피하기 위함이다.

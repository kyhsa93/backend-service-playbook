# API 응답 구조 (Spring Boot)

> 프레임워크 무관 원칙은 루트 [api-response.md](../../../../docs/architecture/api-response.md) 참고.

## 페이지네이션 — 현재 구현

`account/interfaces/rest/AccountController.java`의 `getTransactions`:

```java
@GetMapping("/{accountId}/transactions")
public GetTransactionsResult getTransactions(
        @RequestHeader("X-User-Id") String requesterId,
        @PathVariable String accountId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int take
) {
    return getTransactionsService.getTransactions(accountId, requesterId, page, take);
}
```

`page`(0부터 시작)/`take` 쿼리 파라미터를 `@RequestParam(defaultValue = ...)`로 받는 방식은 루트 원칙과 정확히 일치한다. Spring MVC가 자동으로 `int`로 변환하므로 별도 파싱 코드가 필요 없다.

```
GET /accounts/{accountId}/transactions?page=0&take=20
```

---

## 목록 조회 응답 — Result record

`account/application/query/GetTransactionsResult.java`가 응답 스키마를 정의한다(레코드 정의는 실제로는 아래와 유사한 구조):

```java
public record GetTransactionsResult(List<TransactionSummary> transactions, long count) {
    public record TransactionSummary(
            String transactionId, String type, MoneyResult amount, LocalDateTime createdAt) {}
    public record MoneyResult(long amount, String currency) {}
}
```

JSON 직렬화 결과:

```json
{
  "transactions": [
    { "transactionId": "...", "type": "DEPOSIT", "amount": { "amount": 10000, "currency": "KRW" }, "createdAt": "..." }
  ],
  "count": 2
}
```

- 키 이름이 도메인 객체명 복수형(`transactions`)인 것, `result`/`data` 같은 범용 키를 쓰지 않는 것 모두 루트 원칙을 따른다.
- `count`는 `AccountRepository.countTransactions(accountId)`로 별도 조회한 전체 건수이며 현재 페이지 크기가 아니다.

---

## 단건 조회 응답 — 래퍼 없음

`GetAccountService.getAccount()`가 반환하는 `GetAccountResult`를 Controller가 그대로 반환한다 (`{ success: true, data: {...} }` 같은 범용 래퍼로 감싸지 않음):

```java
@GetMapping("/{accountId}")
public GetAccountResult getAccount(@RequestHeader("X-User-Id") String requesterId, @PathVariable String accountId) {
    return getAccountService.getAccount(accountId, requesterId);
}
```

Spring이 `GetAccountResult` record를 Jackson으로 자동 직렬화하여 도메인 객체를 직접 반환하는 형태의 JSON을 만든다. 에러/정상 응답의 구분은 HTTP 상태 코드(`@ExceptionHandler`가 매핑)가 담당하므로 래퍼가 불필요하다.

---

## Repository 조회 메서드 — 알려진 gap

루트 원칙: 목록 조회 메서드는 **도메인 객체 배열 + count**를 반환해야 하고, 단건 조회는 `find<Noun>s(take: 1)` 패턴으로 통일해야 한다(별도 `findOne` 금지).

현재 `AccountRepository`는 이 패턴에서 벗어나 있다:

```java
public interface AccountRepository {
    Optional<Account> findByAccountIdAndOwnerId(String accountId, String ownerId);  // 단건 전용 메서드
    List<Account> findAll(AccountFindQuery query);
    long countAll(AccountFindQuery query);
    ...
}
```

`findByAccountIdAndOwnerId`는 Spring Data 관례(파생 쿼리 메서드)를 따른 별도의 단건 조회 메서드로, 루트가 명시한 "조회는 `find<Noun>s` 하나만" 규칙과 다르다. Java/Spring Data JPA 생태계에서는 `findBy...` 파생 쿼리가 워낙 자연스럽고 타입 안전하기 때문에, 이 이탈은 실용적 트레이드오프로 볼 수 있다 — 다만 루트 문서의 "단일 조회 경로" 취지를 살리려면 아래처럼 `findAll(query)`에 `accountId`+`ownerId` 필터를 태워 단건도 같은 경로로 통일할 수 있다:

```java
// 대안 — find<Noun>s 하나로 통일
public interface AccountRepository {
    List<Account> findAll(AccountFindQuery query);   // accountId + ownerId 필터 + take:1
    long countAll(AccountFindQuery query);
    void save(Account account);
}

// 호출 측
Account account = accountRepository
        .findAll(new AccountFindQuery(0, 1, accountId, ownerId, null))
        .stream().findFirst()
        .orElseThrow(() -> new AccountException(...));
```

이 저장소는 현재 `findByAccountIdAndOwnerId`를 유지하고 있다 — 두 방식 모두 유효한 선택이며, 새 도메인을 추가할 때는 팀 컨벤션에 맞춰 일관되게 하나를 선택한다.

---

## 동적 필터 조건 패턴

`AccountRepositoryImpl.buildJpql()`이 값이 있을 때만 조건을 추가하는 패턴을 구현한다:

```java
if (query.accountId() != null && !query.accountId().isBlank()) sb.append(" AND a.accountId = :accountId");
if (query.ownerId() != null && !query.ownerId().isBlank()) sb.append(" AND a.ownerId = :ownerId");
if (query.status() != null && !query.status().isEmpty()) sb.append(" AND a.status IN :status");
```

`AccountFindQuery`(`account/domain/AccountFindQuery.java`)가 이 동적 쿼리의 입력 record다:

```java
public record AccountFindQuery(int page, int take, String accountId, String ownerId, List<String> status) {}
```

---

### 관련 문서

- [repository-pattern.md](repository-pattern.md) — Repository 메서드 설계, 단건 조회 패턴
- [layer-architecture.md](layer-architecture.md) — Query Service, Result 객체
- [cqrs-pattern.md](cqrs-pattern.md) — Query Service가 Result를 반환하는 이유

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
- `count`는 `AccountRepository.findTransactions(accountId, page, take)`가 반환하는 `TransactionsWithCount.count()`(전체 건수)이며 현재 페이지 크기가 아니다.

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

## Repository 조회 메서드 — `find<Noun>s` 하나로 통일됨

루트 원칙: 목록 조회 메서드는 **도메인 객체 배열 + count**를 반환해야 하고, 단건 조회는 `find<Noun>s(take: 1)` 패턴으로 통일해야 한다(별도 `findOne` 금지).

`AccountRepository`는 이 원칙을 그대로 따른다:

```java
public interface AccountRepository {
    AccountsWithCount findAccounts(AccountFindQuery query);
    void saveAccount(Account account);
    ...
}

public record AccountsWithCount(List<Account> accounts, long count) {}
```

루트가 명시한 "조회는 `find<Noun>s` 하나만" 규칙에 맞춰, 단건 조회는 `findAccounts(query)`를 `accountId`+`ownerId`+`take:1` 필터로 호출한 뒤 첫 번째 결과를 꺼내는 방식으로 통일한다:

```java
// 호출 측 — application/command/DepositService.java 등에서 반복되는 패턴
Account account = accountRepository
        .findAccounts(new AccountFindQuery(0, 1, accountId, ownerId, null))
        .accounts().stream().findFirst()
        .orElseThrow(() -> new AccountException(...));
```

`Transaction`(하위 Entity) 조회도 동일한 이유로 `findTransactions`/`countTransactions` 두 메서드를 `findTransactions` 하나로 병합해 `TransactionsWithCount`(목록 + count)를 반환한다. 상세는 [repository-pattern.md](repository-pattern.md) 참고.

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

## harness 검증

`harness/src/rules/NoGenericResponseKeys.java`(rule: `no-generic-response-keys`)가 `*Result`/`*Response`/`*WithCount` 네이밍 관례를 따르는 record의 최상위 컴포넌트 중 `List<...>` 타입 필드명이 `result`/`data`/`items` 같은 범용 키가 아닌지 확인한다 — 위 "목록 조회 응답 형식" 원칙의 회귀 가드다. 단건 응답 안에 중첩된 하위 컬렉션(주문 자신의 line item 같은 도메인 개념)은 최상위 record가 아니므로 검사 대상이 아니다.

`harness/src/rules/QueryHandlerNoRawAggregate.java`(rule: `query-handler-no-raw-aggregate`)가 `application/query/`의 Query Service와 `interfaces/`의 REST Controller가 정의하는 `public` 메서드의 반환 타입이 `domain/`의 raw Aggregate/Entity 클래스(`Account`/`Transaction`/`Card`/`Payment`/`Refund`/`Credential` 등, 하드코딩이 아니라 `domain/`의 `public class` 선언에서 동적으로 수집)를 직접(또는 `List<...>` 같은 제네릭으로 감싸) 노출하지 않는지 확인한다 — Query Service/Controller는 항상 전용 Result/DTO 타입을 반환해야 한다는 원칙의 회귀 가드다.

### 관련 문서

- [repository-pattern.md](repository-pattern.md) — Repository 메서드 설계, 단건 조회 패턴
- [layer-architecture.md](layer-architecture.md) — Query Service, Result 객체
- [cqrs-pattern.md](cqrs-pattern.md) — Query Service가 Result를 반환하는 이유

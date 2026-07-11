# Aggregate ID 생성 (Spring Boot)

> 프레임워크 무관 원칙은 루트 [aggregate-id.md](../../../../docs/architecture/aggregate-id.md) 참고.

## 원칙 요약

- ID는 **Domain 레이어의 정적 팩토리 메서드**에서 생성한다 (Aggregate 생성자가 아니라 `create()` 팩토리).
- 클라이언트가 보낸 ID는 사용하지 않는다. 서버가 생성한다.
- 타입은 `String`, 형식은 **32자리 hex, 하이픈 없음**.

```
"550e8400e29b41d4a716446655440000"   // 올바른 방식
"550e8400-e29b-41d4-a716-446655440000"  // 잘못된 방식 — 하이픈 포함
1, 2, 3                                  // 잘못된 방식 — auto-increment
```

---

## `IdGenerator` 적용 완료 (더 이상 gap 아님)

`account/domain/Account.java`의 `create()`, `Transaction.create()`, `account/infrastructure/notification/.../SentEmail.create()`가 발급하는 ID 전부 아래 `IdGenerator.generate()`(32자리 hex, 하이픈 없음)를 사용한다.

```java
// common/IdGenerator.java — 프레임워크 무의존 순수 유틸
package com.example.accountservice.common;

import java.util.UUID;

public final class IdGenerator {

    private IdGenerator() {}

    public static String generate() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
```

```java
// domain/Account.java — 수정된 create()
public static Account create(String ownerId, String email, String currency) {
    Account account = new Account();
    account.accountId = IdGenerator.generate();   // 32자리 hex, 하이픈 없음
    ...
}
```

`IdGenerator`는 `common` 패키지(도메인에 속하지 않는 프로젝트 공통 유틸)에 두고 어떤 Spring/JPA 타입도 import하지 않는다. Domain 레이어(`Account`, `Transaction`)는 이 유틸만 static 호출하므로 순수성을 유지한다.

---

## DB 복원 시 — ID를 새로 발급하지 않는다

Repository 구현체가 JPA 엔티티를 Aggregate로 매핑할 때는 이미 저장된 `accountId`를 그대로 사용한다. `AccountJpaRepository`/`AccountRepositoryImpl`은 저장 시 `accountId` 컬럼 값을 그대로 보존하며, JPA의 `@Id` 대리키(`Long id`, `GenerationType.IDENTITY`)는 DB 내부용 PK일 뿐 도메인 식별자가 아니다.

```java
@Id
@GeneratedValue(strategy = GenerationType.IDENTITY)
private Long id;              // DB 전용 대리키 — 외부에 노출하지 않음

@Column(nullable = false, unique = true)
private String accountId;     // 도메인 식별자 — API 응답/이벤트/외부 참조에 사용
```

이 두 ID를 분리하는 이유:
- `id`(auto-increment)는 Hibernate가 영속성 컨텍스트 식별과 인덱싱에 쓰는 내부 값으로, JPA `@Entity`가 대리키를 요구하는 관례를 따른 것이다.
- `accountId`(32자리 hex)는 API 응답, 도메인 이벤트, 다른 Aggregate에서의 참조에 사용하는 외부 식별자다. auto-increment 숫자를 외부에 노출하면 레코드 수·생성 순서가 드러나는 보안 문제가 생긴다.

---

## 하위 Entity ID

`Transaction`(계좌의 하위 Entity)도 동일하게 `IdGenerator.generate()`로 생성한 32자리 hex 문자열을 `transactionId`로 갖는다. `Transaction.create()`는 `package-private static` 메서드로, `Account` Aggregate Root를 통해서만 생성된다 (`Account.deposit()`/`Account.withdraw()` 내부에서 호출).

---

### 관련 문서

- [tactical-ddd.md](tactical-ddd.md) — Aggregate 정적 팩토리 패턴
- [repository-pattern.md](repository-pattern.md) — Repository의 ID 처리
- [directory-structure.md](directory-structure.md) — `common/` 패키지 배치 기준

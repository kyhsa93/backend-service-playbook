# Repository 패턴 (Go)

원칙은 루트 [repository-pattern.md](../../../../docs/architecture/repository-pattern.md)를 따른다: Aggregate Root 단위로 1개 인터페이스 + 1개 구현체, 인터페이스는 domain 레이어, 구현체는 infrastructure 레이어. TypeScript의 `abstract class`를 DI 토큰으로 쓰는 자리에, Go는 **domain 패키지의 `interface` 타입**을 그대로 쓴다 — 별도 토큰 개념이 없다. `guide.md`에 있던 요지를 이 문서로 옮기고 실제 코드 기준으로 확장했다.

---

## 인터페이스 위치 — `internal/domain/account/repository.go`

```go
package account

import "context"

type FindQuery struct {
	Page      int
	Take      int
	AccountID string
	OwnerID   string
	Status    []Status
}

type Repository interface {
	FindAccounts(ctx context.Context, q FindQuery) ([]*Account, int, error)
	Save(ctx context.Context, account *Account) error
	FindTransactions(ctx context.Context, accountID string, page, take int) ([]Transaction, int, error)
}
```

---

## 구현체 위치 — `internal/infrastructure/persistence/account_repository.go`

```go
type AccountRepository struct {
	db *sql.DB
}

// 컴파일 타임 interface 충족 검증 — TypeScript/Java의 `implements` 키워드가
// 컴파일러 수준에서 자동으로 하는 일을 Go는 이 한 줄로 명시적으로 얻는다.
var _ account.Repository = (*AccountRepository)(nil)

func NewAccountRepository(db *sql.DB) *AccountRepository {
	return &AccountRepository{db: db}
}
```

`var _ account.Repository = (*AccountRepository)(nil)`을 지워도 프로그램은 똑같이 동작하지만, `AccountRepository`가 인터페이스를 실수로 만족하지 못하게 되는 리팩토링(메서드 시그니처 변경 등)을 그 자리에서 컴파일 에러로 잡아준다 — 런타임까지 갈 필요가 없다.

---

## "DI 바인딩" 대신 — 인터페이스 타입으로 주입받기

root는 "Application Service는 abstract class 타입으로 Repository를 주입받는다"고 한다. Go에서는 생성자 함수의 파라미터 타입을 인터페이스로 선언하는 것으로 동일한 효과를 낸다 — 별도의 "바인딩" 등록 단계가 없다.

```go
// internal/application/command/deposit_handler.go
type DepositHandler struct {
	repo account.Repository  // 구체 타입이 아니라 인터페이스 타입
}

func NewDepositHandler(repo account.Repository) *DepositHandler {
	return &DepositHandler{repo: repo}
}
```

`main.go`에서 `persistence.NewAccountRepository(db, outboxWriter)`(구체 타입 `*persistence.AccountRepository`)를 넘기면, Go 컴파일러가 구조적 타이핑으로 `account.Repository` 인터페이스 만족 여부를 그 호출 지점에서 검사한다. NestJS의 `@Inject(OrderRepository)` 토큰 등록, providers 배열 같은 개념 자체가 필요 없다.

---

## 메서드 네이밍 — root 규칙과의 대응

root는 `find<Noun>s`(항상 목록 반환, 단건도 `take:1`로 흉내낸다) / `save<Noun>` / `delete<Noun>` 세 패턴만 쓰라고 한다. 이 저장소의 `account.Repository`는 그 규칙을 그대로 따른다:

| root 규칙 | 이 저장소의 실제 메서드 | 대응 |
|---|---|---|
| `find<Noun>s`(단건도 목록으로) | `FindAccounts(ctx, FindQuery)` | 동일 — 조회는 이 메서드 하나뿐이다. `FindQuery.Take`/필터 필드로 목록/단건을 모두 표현한다 |
| `delete<Noun>` | 없음 | `Account`는 `Close()`로 상태만 바꾸고 실제로 행을 지우지 않는다 — 계좌 도메인 특성상 "삭제" 유스케이스 자체가 없다 |
| `save<Noun>` | `Save` | 동일 — upsert(`ON CONFLICT ... DO UPDATE`)로 생성/수정을 겸한다 |

**단건 조회는 별도 메서드가 아니라 `FindAccounts`를 `Take: 1`로 호출해 재사용한다.** java/kotlin-springboot의 `findAccounts(...).stream().findFirst().orElseThrow(...)`와 동일한 역할을 하는 헬퍼가 `internal/domain/account/repository.go`의 `FindOne(ctx, q, accountID, ownerID)`다 — `FindAccounts`를 `FindQuery{AccountID: accountID, OwnerID: ownerID, Take: 1}`로 호출하고, 결과가 없으면 `ErrNotFound`를 반환한다. Go에는 Stream이 없으므로 이 반복 패턴(첫 결과 꺼내기 + 없으면 에러)을 자유 함수로 추출해 각 호출부(Command/Query Handler, `acl/account_adapter.go`)가 중복 없이 재사용한다:

```go
// internal/application/command/deposit_handler.go
a, err := account.FindOne(ctx, h.repo, cmd.AccountID, cmd.RequesterID)
if err != nil {
	return nil, fmt.Errorf("deposit: %w", err)
}
```

`Repository`에 update 메서드가 별도로 없는 것은 root 원칙과 일치한다 — 상태 변경은 항상 Aggregate 메서드(`Deposit`, `Suspend` 등) 호출 후 `Save()`로 반영한다.

---

## 동적 필터 — `FindAccounts`의 조건부 WHERE

root의 "값이 있을 때만 조건 추가" 패턴을 Go는 슬라이스 append로 구현한다(`account_repository.go`):

```go
func (r *AccountRepository) FindAccounts(ctx context.Context, q account.FindQuery) ([]*account.Account, int, error) {
	args := []any{}
	where := []string{"deleted_at IS NULL"}
	i := 1

	if q.AccountID != "" {
		where = append(where, fmt.Sprintf("id = $%d", i))
		args = append(args, q.AccountID)
		i++
	}
	if q.OwnerID != "" {
		where = append(where, fmt.Sprintf("owner_id = $%d", i))
		args = append(args, q.OwnerID)
		i++
	}
	if len(q.Status) > 0 {
		placeholders := make([]string, len(q.Status))
		for j, s := range q.Status {
			placeholders[j] = fmt.Sprintf("$%d", i)
			args = append(args, string(s))
			i++
		}
		where = append(where, fmt.Sprintf("status IN (%s)", strings.Join(placeholders, ",")))
	}

	whereClause := strings.Join(where, " AND ")
	// ... whereClause를 fmt.Sprintf로 SQL에 삽입, args는 QueryContext의 가변 인자로 전달
}
```

주의할 점: `$N` 플레이스홀더 번호(`i`)는 조건이 추가될 때마다 증가시켜야 하고, `WHERE` 절 문자열 자체는 `fmt.Sprintf`로 조립하되 **값은 절대 문자열에 직접 삽입하지 않는다** — 항상 `args`를 통해 파라미터 바인딩한다. 컬럼명/절 구조를 조합하는 것과 사용자 입력값을 바인딩하는 것을 혼동하면 SQL 인젝션으로 이어진다.

---

## Soft Delete — 조회 시 필터링

`internal/infrastructure/persistence/account_repository.go`의 모든 조회 쿼리는 `deleted_at IS NULL`을 기본 조건에 포함한다(`FindAccounts`의 `where` 슬라이스 초기값). 상세 스키마와 삭제 흐름은 [persistence.md](persistence.md) 참고 — 다만 이 예제는 Account를 물리적으로 삭제하는 유스케이스가 없어(계좌는 `Close()`로 상태 전환만 함) `deleted_at` 컬럼이 있어도 실제로 채워지는 경로는 아직 없다.

---

### 관련 문서

- [tactical-ddd.md](tactical-ddd.md) — Aggregate Root 설계와 Repository의 관계
- [layer-architecture.md](layer-architecture.md) — 레이어 의존 방향, 인터페이스 위치
- [persistence.md](persistence.md) — 트랜잭션, Soft Delete, 마이그레이션
- [domain-events.md](domain-events.md) — Repository의 `Save`가 이벤트를 Outbox에 같은 트랜잭션으로 저장하는 지점
- [aggregate-id.md](aggregate-id.md) — Repository가 ID를 새로 발급하지 않는 원칙

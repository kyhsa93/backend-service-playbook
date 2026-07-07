# AI Agent 자기 검토 체크리스트 (Go)

작업 완료 후, 아래 체크리스트를 순서대로 검토한다.
각 항목을 점검하여 위반이 발견되면 즉시 수정한 뒤 다음 항목으로 넘어간다.

### 검증 수행 규칙

- 각 STEP을 검증할 때 반드시 해당 파일을 Read 도구로 읽고 실제 코드와 대조한다.
- 코드를 읽지 않고 통과 처리하는 것은 금지한다.
- 위반 발견 시 즉시 수정한 후 다음 STEP으로 넘어간다.
- `implementations/go/harness/main.go`가 자동 검증하는 항목(파일명 snake_case, 4레이어 디렉토리 존재, Repository/Handler/Scheduler/Task Controller/Event Handler 배치)은 `./harness.sh <projectRoot>`로도 교차 확인한다 — 다만 harness는 구조·배치만 검사하고 의미적 규칙(메서드 네이밍, 에러 처리, 트랜잭션 등)은 검사하지 않으므로 이 체크리스트로 보완한다.

---

## STEP 1 — 파일 구조 및 네이밍

**관련 문서**: [conventions.md](./conventions.md) · [architecture/directory-structure.md](./architecture/directory-structure.md)

```
[ ] 파일명이 snake_case.go 형식이 아닌 것이 있는가?
    → 있다면 snake_case.go로 변경 (예: get_transactions_handler.go)
[ ] 패키지명이 소문자 단일 단어(언더스코어/캐멀케이스 없음)이고 디렉토리명과 일치하는가?
    → package account, package persistence 등
[ ] Domain 레이어(internal/domain/<domain>/)에 Aggregate Root, Entity, Value Object, Domain Event, sentinel error, Repository interface가 모두 있는가?
    → <aggregate-root>.go, transaction.go(Entity), money.go(VO), events.go, errors.go, repository.go
[ ] 상태(status)류 값 객체가 별도 파일(<domain>_status.go)로 분리되어 있는가?
    → account_status.go처럼 도메인 파일 하나에 여러 개념을 몰아넣지 않는다
[ ] Application 레이어 파일명이 <verb>_<noun>_handler.go 형식이고 command/ 또는 query/에 배치되어 있는가?
    → create_account_handler.go, get_transactions_handler.go
[ ] Result DTO들이 application/query/result.go(또는 유사 파일)에 모여 있는가?
[ ] Technical Service 인터페이스(알림, 시크릿, 스토리지 등)가 사용하는 쪽 application 패키지에 정의되어 있는가?
    → command.OutboxRelay, command.SecretService, command.StorageService
[ ] Infrastructure 레이어 파일이 internal/infrastructure/<concern>/<aggregate>_repository.go 형식으로 배치되어 있는가?
    → persistence/account_repository.go, notification/service.go
[ ] Interface 레이어 파일이 internal/interface/http/<domain>_handler.go, dto.go, router.go로 배치되어 있는가?
[ ] 도메인이 여러 개로 늘어났다면 application/command/<domain>/, infrastructure/persistence/<domain>_repository.go처럼 세분화를 검토했는가?
    → 단일 도메인뿐이면 평평한 구조 그대로 두어도 된다(YAGNI)
[ ] 공용 코드(ID 생성 등)가 internal/common/처럼 프레임워크 무의존 순수 함수 전용 패키지에 있는가?
    → 도메인 전용 코드를 공유 패키지로 옮기지 않았는가
[ ] 타입명이 PascalCase, 공개 함수/메서드가 PascalCase, 비공개 함수/메서드가 camelCase인가?
[ ] 에러 변수명이 ErrXxx 형식인가? (ErrNotFound, ErrInsufficientBalance 등)
[ ] 인터페이스명이 동사+er보다 역할 명사를 우선하는가? (Repository, OutboxRelay — Fetcher/Sender류 지양)
[ ] Repository 구현체에 컴파일 타임 인터페이스 검증(`var _ <domain>.Repository = (*XRepository)(nil)`)이 있는가?
```

---

## STEP 2 — Domain 레이어

**관련 문서**: [architecture/tactical-ddd.md](architecture/tactical-ddd.md) · [architecture/layer-architecture.md](architecture/layer-architecture.md) · [architecture/aggregate-id.md](architecture/aggregate-id.md) · [domain-service.md](../../../docs/architecture/domain-service.md) (루트 공용)

```
[ ] Domain 패키지가 표준 라이브러리 기본 패키지 + 최소 의존성(google/uuid 등)만 import하는가?
    → log, net/http, context, os, database/sql 등 프레임워크/인프라 import가 없는지 확인
[ ] 불변식이 Aggregate 메서드 내부에서만 검증되는가?
    → Handler나 다른 패키지가 필드를 직접 대입해 상태를 바꾸고 있지 않은지 확인
[ ] Aggregate 외부에 노출하면 안 되는 필드(이벤트 슬라이스, 하위 Entity 슬라이스 등)가 unexported(소문자)로 선언되어 있는가?
    → events, transactions 등
[ ] 이벤트/하위 Entity 접근이 DomainEvents()/ClearEvents() 같은 명시적 공개 메서드로만 이루어지는가?
[ ] 신규 생성(`New(...)`)과 DB 복원(`Reconstitute(...)`)이 별도 함수로 분리되어 있는가?
    → New()는 이벤트를 발행하고 ID를 새로 발급, Reconstitute()는 이벤트 없이 상태만 채운다
[ ] New(...)의 파라미터 목록에 ID가 없는가? (클라이언트가 제공한 ID를 받지 않는다)
[ ] Aggregate ID가 UUID v4에서 하이픈을 제거한 32자리 hex 문자열로 생성되는가? (`common.NewID()`)
    → `uuid.NewString()`을 하이픈 포함 그대로 쓰고 있지 않은가 (이 저장소 examples/의 알려진 격차 — 새로 작성하는 코드는 재현하지 않는다)
[ ] Value Object 메서드가 값 리시버(`(m Money)`)를 사용해 원본을 변형하지 않고 새 값을 반환하는가?
[ ] Value Object에 속성 기반 동등성 비교(`Equals(other T) bool`) 메서드가 있는가?
[ ] Domain Event 타입명이 과거형(AccountCreated, MoneyDeposited)인가?
[ ] Domain Event들이 공통 마커 인터페이스(예: `isAccountDomainEvent()`)를 공유해 임의 타입의 오혼입을 막는가?
[ ] Repository interface가 domain 패키지에 시그니처만 정의되어 있고 구현이 없는가?
[ ] Aggregate 간 참조가 ID 참조만 사용하고 다른 Aggregate 객체를 직접 필드로 들고 있지 않은가?
[ ] 여러 Aggregate가 같은 패키지에 섞여 있지 않은가? (Aggregate 하나당 패키지 분리가 원칙)
[ ] 같은 패키지 내부 코드가 Aggregate 필드를 메서드를 거치지 않고 직접 조작하지 않는가?
    → Go는 패키지 단위 캡슐화만 지원하므로 이 규율은 코드 리뷰로 보완해야 한다
```

---

## STEP 3 — 레이어 아키텍처 / CQRS / 크로스 도메인

**관련 문서**: [architecture/layer-architecture.md](architecture/layer-architecture.md) · [architecture/cqrs-pattern.md](architecture/cqrs-pattern.md) · [architecture/cross-domain.md](architecture/cross-domain.md) · [architecture/domain-events.md](architecture/domain-events.md)

```
[ ] 의존 방향이 Interface → Application → Domain이고, Infrastructure가 Domain의 interface를 구현하는가?
    → domain 패키지의 import 목록에 infrastructure/interface/application이 없는지 확인
[ ] CommandHandler/QueryHandler가 구조체 + `Handle(ctx context.Context, cmd/query X) (결과, error)` 시그니처를 따르는가?
[ ] Handler가 비즈니스 로직(상태 전이 조건 검사, 금액 계산 등)을 직접 수행하는가?
    → 있다면 Aggregate 도메인 메서드로 이동
[ ] Handler가 (1) Repository 조회 → (2) 도메인 메서드 호출 → (3) Repository 저장 → (4) 부가 효과(알림 등) 순서로 조율만 하는가?
[ ] Handler가 에러를 그대로 반환하거나 `fmt.Errorf("...: %w", err)`로 래핑해 반환하는가? (패닉으로 던지지 않는가)
[ ] 별도 Query interface 없이 Command와 동일한 Repository를 재사용하고 있다면, 이것이 이 저장소의 알려진 편차임을 인지했는가?
    → 읽기 모델을 별도 저장소로 분리해야 하는 시점이 되면 `application/query/`에 Query interface + `infrastructure/`에 읽기 전용 구현체를 새로 만든다
[ ] CommandBus/QueryBus 같은 런타임 라우팅 계층을 새로 만들려 하지 않는가?
    → Handler 인스턴스를 필드로 직접 보유해 호출한다 (Bus 불필요 — 컴파일 타임에 타입이 이미 확정됨)
[ ] 다른 도메인의 Repository/Service를 Application 레이어에서 직접 참조하는가?
    → 있다면 Adapter 패턴으로 변경: 호출하는 쪽 application 패키지에 interface, 호출하는 쪽 infrastructure 패키지에 구현체
[ ] Adapter interface가 호출받는 쪽의 전체 API가 아니라 호출하는 쪽이 필요로 하는 최소 모양만 선언하는가?
[ ] Adapter 구현체에도 `var _ Interface = (*Impl)(nil)` 컴파일 타임 검증이 있는가?
[ ] 도메인 이벤트가 Aggregate 도메인 메서드 내부에서만 생성되는가?
    → Handler가 직접 이벤트 구조체를 만들어 append하고 있지 않은가
[ ] 이벤트를 Outbox 없이 Handler가 Repository 저장 직후 별도 동기 호출로 알림을 보내고 있다면, 이것이 dual-write 알려진 격차임을 인지했는가?
    → 실패해도 무방한 부가 기능(이메일 알림 등)이면 현재 패턴이 실용적 절충일 수 있다. 법적 통지처럼 중요한 이벤트라면 Outbox(Repository 저장과 같은 트랜잭션에 이벤트 적재 + 별도 Relay) 패턴 도입을 검토한다
[ ] 패키지 순환 의존(import cycle)이 있는가?
    → Go는 `forwardRef()` 같은 우회 수단이 없다. 공유 개념을 세 번째 패키지로 추출하거나, Adapter로 한쪽 방향을 강제하거나, 비동기(Integration Event) 전환을 검토한다
```

---

## STEP 4 — Repository 패턴

**관련 문서**: [architecture/repository-pattern.md](architecture/repository-pattern.md) · [architecture/persistence.md](architecture/persistence.md)

```
[ ] Repository가 Aggregate Root 단위로 정의되어 있는가? (테이블/Entity 단위 X)
[ ] Repository interface가 domain 패키지에, 구현체가 infrastructure 패키지에 있는가?
[ ] 구현체에 `var _ <domain>.Repository = (*XRepository)(nil)` 컴파일 타임 검증이 있는가?
[ ] Repository 메서드가 FindByID/FindAll/Save(/필요 시 별도 상태 전환 메서드) 패턴을 따르는가?
    → root 원칙(find<Noun>s 단일 메서드 + take:1)을 더 엄격히 따르고 싶다면 단일 `Find(ctx, query)`로 통합하는 것도 가능하나, 한 저장소 안에서는 일관성을 유지한다
[ ] update<Noun> 성격의 메서드가 있는가?
    → 있다면 제거. 조회 후 Aggregate 도메인 메서드로 상태 변경, Save로 반영
[ ] Repository 구현체가 DB row를 도메인 Aggregate로 변환하는가? (`Reconstitute(...)` 사용, row를 그대로 반환하지 않는지)
[ ] 동적 WHERE 조건이 값이 있을 때만(zero value가 아닐 때만) 슬라이스에 append되는 패턴을 따르는가?
    → `$N` 플레이스홀더 번호가 조건 추가마다 올바르게 증가하는가, 값이 문자열에 직접 삽입되지 않고 항상 args로 바인딩되는가(SQL 인젝션 방지)
[ ] 목록 조회 메서드가 (도메인 객체 슬라이스, count, error) 형태로 반환하는가?
[ ] Soft Delete 컬럼(deleted_at)이 조회 쿼리 WHERE에 기본 포함되는가? (`deleted_at IS NULL`)
[ ] hard delete(`DELETE FROM ...`)를 사용하지 않고 soft delete(UPDATE ... SET deleted_at)를 사용하는가?
[ ] 여러 Repository를 하나의 트랜잭션으로 묶어야 하는 Command가 있는가?
    → 현재 이 저장소는 `context.Context` 기반 트랜잭션 전파(`database.WithTx`)가 구현되어 있지 않다(알려진 격차). 단일 Repository 범위를 넘는 트랜잭션이 필요해지면 이 패턴을 새로 도입한다
[ ] 마이그레이션이 순번 SQL 파일(`migrations/000X_*.sql`)로 관리되는가? (자동 스키마 동기화 사용 금지 — `database/sql`은 애초에 자동 동기화 기능이 없다)
```

---

## STEP 5 — 의존성 조립 (DI 컨테이너 없음)

**관련 문서**: [architecture/module-pattern.md](architecture/module-pattern.md) · [architecture/shared-modules.md](architecture/shared-modules.md) · [architecture/bootstrap.md](architecture/bootstrap.md)

```
[ ] 새 Infrastructure 구현체가 `New...()` 생성자 함수로 만들어지는가?
[ ] Application Handler 생성자가 구체 타입이 아니라 domain 패키지의 interface 타입으로 의존성을 주입받는가?
    → `repo account.Repository`이지 `repo *persistence.AccountRepository`가 아니다
[ ] main.go(또는 router.go)의 조립 순서가 의존 방향과 정확히 일치하는가? (DB → Infrastructure → Application → Interface → 서버 시작)
[ ] main()이 배선 외의 비즈니스 로직이나 조건 분기를 포함하는가?
    → 있다면 제거. main()은 생성자를 순서대로 호출하고 서버를 시작하는 것만 담당
[ ] 공유 인스턴스(DB 커넥션 풀, logger 등)가 여러 도메인의 생성자에 동일한 값으로 전달되는가?
    → "공유"는 NestJS의 `@Global()` 선언이 아니라 같은 변수를 여러 생성자 인자로 넘기는 것으로 이루어진다
[ ] 도메인 전용 코드가 `internal/common/` 같은 공유 패키지로 잘못 옮겨져 있지 않은가?
    → 공유 패키지는 실제로 두 도메인 이상이 필요로 할 때만 만든다(YAGNI)
[ ] 패키지 순환 의존이 있는가?
    → Go는 우회 수단이 없다. 공유 개념 추출, Adapter로 단방향화, 또는 비동기 전환 중 하나로 즉시 재설계한다
[ ] 다른 도메인 호출이 필요한 경우 Adapter interface가 호출하는 쪽 application 패키지에, 구현체가 호출하는 쪽 infrastructure 패키지에 있는가?
[ ] 여러 도메인이 생겨 `application/command/`, `application/query/`가 평평한 구조로 남아있다면 `command/<domain>/`, `query/<domain>/` 세분화가 필요한 시점인지 검토했는가?
[ ] 리플렉션 기반 DI 컨테이너나 서비스 로케이터를 직접 구현하려 하지 않았는가? (Go에서는 생성자 체이닝을 그대로 받아들인다)
```

---

## STEP 6 — Go 타이핑 및 도메인 모델링 패턴

**관련 문서**: [conventions.md](./conventions.md)

```
[ ] 실패 가능성이 있는 함수/메서드가 모두 `error`를 반환값으로 명시하는가?
[ ] 레이어 경계를 넘는 함수의 첫 인자가 `context.Context`인가? (Repository, Handler, Adapter, Technical Service 전부)
[ ] DTO/Result/Command/Query struct 필드가 exported(PascalCase)로 선언되어 있는가?
[ ] `any`(interface{})를 근거 없이 사용한 곳이 있는가?
    → 있다면 구체 타입 또는 명확한 인터페이스로 교체
[ ] nullable 값 표현이 zero value(`""`, `0`, `nil`) 관용으로 충분한지, 아니면 포인터(`*string` 등)로 명시적 null이 필요한지 의도적으로 판단했는가?
[ ] Value Object 메서드가 값 리시버를 사용해 원본을 변형하지 않는가?
[ ] Aggregate 메서드가 포인터 리시버(`(a *Account)`)를 사용해 상태를 변경하는가?
[ ] 복잡한 반환 타입에 named struct/type을 정의해 가독성을 높였는가? (익명 struct 남용 금지)
[ ] enum 성격의 값(Status 등)이 `type Status string` + 상수 그룹으로 정의되어 있는가? (magic string 직접 비교 금지)
```

---

## STEP 7 — 에러 처리

**관련 문서**: [architecture/error-handling.md](architecture/error-handling.md)

```
[ ] 새 에러가 sentinel error(`var ErrXxx = errors.New("...")`)로 정의되어 있는가?
[ ] 에러 메시지가 소문자로 시작하고 마침표 등 구두점이 없는가? (Go 표준 컨벤션)
[ ] 상위 레이어에서 에러를 감쌀 때 `fmt.Errorf("<작업 설명>: %w", err)`로 래핑해 원본을 보존하는가?
[ ] 이미 sentinel error인 값을 불필요하게 다시 래핑하고 있지 않은가? (정보 없이 errors.Is 체인만 길어짐)
[ ] Interface 레이어(HTTP Handler)에서만 `errors.Is`로 HTTP 상태 코드를 매핑하는가?
    → Domain/Application 레이어가 `net/http` 상태 코드나 HTTP 개념을 알고 있지 않은지 확인
[ ] 매핑에 없는 에러(default 분기)가 500 + 클라이언트에 내부 구현 세부사항을 노출하지 않는 일반 메시지로 처리되는가?
[ ] 표준 에러 응답 JSON 스키마(`{statusCode, code, message, error}`)가 필요한 작업이면 도입했는가?
    → 현재 이 저장소는 `http.Error`로 평문 텍스트만 반환하는 알려진 격차가 있다. 클라이언트가 `code`로 분기해야 하는 요구사항이 있다면 JSON 스키마로 전환한다
[ ] Aggregate/Repository/Handler 어디서든 `panic`으로 정상적인 실패를 표현하고 있지 않은가? (`error` 반환이 원칙)
[ ] 새 sentinel error를 추가했다면 해당 도메인의 `errors.go`에 함께 모아두었는가?
```

---

## STEP 8 — REST API 엔드포인트

**관련 문서**: [architecture/api-response.md](architecture/api-response.md) · [conventions.md](./conventions.md) 섹션 5 · [conventions.md](../../../docs/conventions.md) (루트 공용) 섹션 1

```
[ ] URL이 동사가 아닌 복수 명사 리소스로 구성되어 있는가?
    → 올바른 예: GET /accounts, POST /accounts
    → 잘못된 예: GET /getAccounts, POST /createAccount
[ ] 비 CRUD 행위가 하위 리소스 경로로 표현되는가?
    → 올바른 예: POST /accounts/{id}/deposit, POST /accounts/{id}/suspend
[ ] HTTP 메서드와 응답 코드가 올바른가? (GET/PUT/PATCH 200, POST 201, DELETE 204)
[ ] 라우팅이 `net/http`의 method+path 패턴(`mux.HandleFunc("GET /accounts/{id}", ...)`, Go 1.22+)으로 등록되어 있는가?
[ ] 페이지네이션이 `page`(0-base)/`take` 쿼리 파라미터로 파싱되는가?
    → `r.URL.Query()`에서 직접 파싱, 파싱 실패 시 기본값으로 흡수할지 400으로 처리할지 의도적으로 결정했는가
[ ] 목록 응답이 범용 키(`data`/`result`/`items`) 대신 도메인 복수형 키(`accounts`, `transactions` 등) + `count`로 구성되어 있는가?
[ ] `count`가 페이지 크기가 아니라 필터 적용 후 전체 건수인가?
[ ] 단건 응답이 `{ success, data }` 같은 범용 래퍼 없이 필드를 평탄하게 노출하는가?
[ ] Result 구조체(Application 레이어)가 도메인 Aggregate를 그대로 노출하지 않고 응답 전용 필드로 매핑되는가?
[ ] Interface DTO가 Application Result/Command의 필드를 재선언한 얇은 래퍼이고, Handler가 필드를 명시적으로 매핑하는가?
    → Go에는 상속이 없으므로 TypeScript의 `extends`를 필드 재선언 + 명시적 매핑으로 대신한다
```

---

## STEP 9 — 인증 · 횡단 관심사 · Rate Limiting

**관련 문서**: [architecture/authentication.md](architecture/authentication.md) · [architecture/cross-cutting-concerns.md](architecture/cross-cutting-concerns.md) · [architecture/rate-limiting.md](architecture/rate-limiting.md) · [architecture/observability.md](architecture/observability.md)

```
[ ] 인증 검증이 Interface 레이어(미들웨어)에서만 이루어지는가?
    → Application/Domain 패키지가 `jwt` 관련 패키지를 import하지 않는지 확인
[ ] 인증 미들웨어가 `Authorization: Bearer` 헤더를 추출해 검증한 뒤 `context.WithValue`로 사용자 정보를 다음 핸들러에 전달하는가?
[ ] JWT payload가 `userId` 등 최소한의 정보만 담는가? (역할/이메일 등 민감·가변 정보 금지)
[ ] 인증이 필요한 라우트가 그룹(서브 mux) 단위로 미들웨어에 감싸여 있는가?
    → 개별 핸들러마다 따로 감싸 새 엔드포인트 추가 시 누락되는 위험을 만들지 않는가
[ ] 검증되지 않은 헤더(`X-User-Id` 등)를 인증으로 착각해 그대로 신뢰하고 있지 않은가?
    → 이 저장소 `examples/`의 알려진 격차다. 새로 작성하는 기능이라면 JWT 기반 미들웨어로 구현했는가
[ ] 미들웨어 체인이 관심사별로 하나씩 분리되어 있는가? (Correlation ID → 인증 → Rate Limit → Handler → 로깅 순서가 합리적인가)
[ ] Rate Limiting이 필요한 엔드포인트에 `golang.org/x/time/rate` 기반 토큰 버킷이 적용되어 있는가?
[ ] 클라이언트별 제한이 필요한 경우 limiter map에 cleanup(오래된 항목 제거) 로직이 있는가? (없으면 메모리 누수)
[ ] 쓰기 엔드포인트가 읽기보다 엄격한 제한을 받는가?
[ ] 헬스체크/내부 엔드포인트가 인증·Rate Limit 미들웨어에서 제외되어 있는가?
[ ] Domain 레이어에서 로거·HTTP·인증 등 횡단 관심사를 import하고 있지 않은가?
```

---

## STEP 10 — import 구성

**관련 문서**: [conventions.md](./conventions.md) 섹션 7

```
[ ] import가 표준 라이브러리 → 서드파티 → 내부 패키지 3그룹(그룹 사이 빈 줄)으로 정렬되어 있는가?
[ ] 각 그룹 내부가 알파벳 순으로 정렬되어 있는가? (`goimports` 실행 결과와 다르지 않은지)
[ ] 내부 패키지 alias(`httphandler "internal/interface/http"` 등)가 이름 충돌 회피 목적으로만 합리적으로 사용되었는가?
[ ] blank import(`_ "github.com/lib/pq"` 등 드라이버 등록용)가 서드파티 그룹의 올바른 위치에 있는가?
[ ] Domain 레이어 파일이 infrastructure/interface/application 패키지를 import하고 있지 않은가?
[ ] `gofmt -l .`과 `goimports -l .`이 빈 출력을 반환하는가? (포맷팅 위반 없음)
```

---

## STEP 11 — 스케줄링 · Task Queue · Graceful Shutdown

**관련 문서**: [architecture/scheduling.md](architecture/scheduling.md) · [architecture/graceful-shutdown.md](architecture/graceful-shutdown.md) · [architecture/domain-events.md](architecture/domain-events.md) · [architecture/container.md](architecture/container.md)

```
[ ] Scheduler(`*_scheduler.go`)가 infrastructure 레이어(`internal/infrastructure/scheduling/` 등)에 위치하는가?
[ ] Scheduler가 비즈니스 로직을 직접 실행하지 않고 `TaskQueue.Enqueue`만 호출하는가?
[ ] Scheduler의 `time.Ticker` 루프가 `ctx.Done()`으로 종료되는가? (graceful shutdown과 연동)
[ ] Ticker 콜백에서 발생한 에러가 무시되지 않고 `slog.ErrorContext` 등으로 명시적으로 로깅되는가?
    → Cron/Ticker 콜백의 예외는 조용히 삼켜지기 쉬우므로 반드시 직접 로깅한다
[ ] Task 적재가 DB 변경과 같은 트랜잭션 안에서 이루어지는가? (dual-write 방지)
[ ] Task Consumer가 에러를 삼키지 않고 그대로 반환해 재시도/DLQ 메커니즘에 위임하는가?
[ ] 이벤트/Task 핸들러가 멱등하게 구현되어 있는가? (본질적 멱등 / 처리 기록 테이블 / 강한 원자성 중 필요한 단계를 판단했는가)
[ ] `signal.NotifyContext(ctx, syscall.SIGTERM, syscall.SIGINT)`로 종료 신호를 컨텍스트 취소로 변환하는가?
[ ] SIGTERM 수신 시 readiness를 먼저 503으로 전환한 뒤 `srv.Shutdown(ctx)`을 호출하는가? (순서가 뒤바뀌지 않았는가)
[ ] Shutdown 타임아웃이 오케스트레이터의 `terminationGracePeriodSeconds`와 맞춰져 있는가?
[ ] 컨테이너 `ENTRYPOINT`/`CMD`가 exec form(`["/bin/server"]`)으로 되어 있어 SIGTERM이 셸에 가로채이지 않는가?
```

---

## STEP 12 — DB / 인프라 패턴

**관련 문서**: [architecture/persistence.md](architecture/persistence.md) · [architecture/config.md](architecture/config.md) · [architecture/secret-manager.md](architecture/secret-manager.md) · [architecture/local-dev.md](architecture/local-dev.md) · [architecture/container.md](architecture/container.md) · [architecture/observability.md](architecture/observability.md)

```
[ ] 필수 환경 변수가 기동 시 검증되고 실패하면 `log.Fatal`(즉시 종료)로 이어지는가?
    → `os.Getenv`를 검증 없이 바로 `sql.Open` 등에 넘기고 있지 않은가
[ ] 관심사별 Config 구조체(`Load...Config()`)로 환경 변수가 분리되어 있는가?
[ ] 로컬 개발 전용 기본값(`getEnvOr`)과 운영에서 반드시 필요한 값(빈 문자열이면 에러)이 구분되어 있는가?
[ ] 민감 값(DB 비밀번호, JWT secret, API 키)이 하드코딩되어 있지 않은가?
    → 운영 환경에서 AWS Secrets Manager 등으로 조회하는가
[ ] SecretService 조회 결과가 TTL 캐시(`sync.Mutex` + map)로 캐싱되어 반복 조회를 피하는가?
[ ] Domain/Application 패키지에 `os.Getenv` 호출이 없는가? (설정 접근은 Infrastructure/`main.go`에서만)
[ ] `created_at`/`updated_at`/`deleted_at` 컬럼이 스키마에 있고, 조회 쿼리가 `deleted_at IS NULL`로 필터링하는가?
[ ] 마이그레이션이 순번 SQL 파일로 관리되는가? (down 스크립트 부재 등 알려진 격차를 인지하고 있는가)
[ ] 구조화 로그가 `log/slog`로 남는가? (JSON 핸들러, snake_case 필드명, `ctx`를 받는 `InfoContext`/`ErrorContext` 사용)
[ ] Correlation ID가 `context.Context`로 전파되어 모든 로그에 포함되는가?
[ ] 컨테이너 이미지가 멀티스테이지 빌드(`CGO_ENABLED=0` 정적 빌드 + `distroless`/`scratch`)로 구성되어 있는가?
[ ] `ENTRYPOINT`가 exec form이고 이미지에 환경 변수(`ENV DATABASE_URL=...` 등 민감 값)를 굽지 않는가?
[ ] 헬스체크 엔드포인트(`/health/live`, `/health/ready`)가 `net/http`로 직접 구현되어 있는가?
[ ] LocalStack 등 로컬 인프라가 `docker-compose.yml`에 버전 고정 이미지(`:latest` 금지)로 정의되어 있는가?
```

---

## STEP 13 — 테스트 패턴

**관련 문서**: [architecture/testing.md](architecture/testing.md)

```
[ ] Domain 단위 테스트가 `package <domain>_test`(외부 테스트 패키지)로 공개 API만 사용해 작성되어 있는가?
[ ] table-driven test(`[]struct{...}` + `t.Run` 서브테스트) 스타일을 따르는가?
[ ] 기대 에러가 `errors.Is`로 비교되는가? (문자열 비교 금지)
[ ] Application 단위 테스트가 Repository 등 interface를 수동 stub 구조체로 대체하는가? (구체 타입 mock 금지)
[ ] Application 테스트가 비즈니스 로직을 재검증하지 않고 Handler의 호출 순서(조회 → 도메인 메서드 → 저장 → 알림)만 검증하는가?
[ ] E2E 테스트가 testcontainers-go로 실제 Postgres/LocalStack 컨테이너를 띄우는가? (운영 DB 직접 연결 금지)
[ ] E2E 테스트가 `TestMain`에서 컨테이너를 한 번 띄우고 마이그레이션 파일을 순서대로 실행하는가?
[ ] 테스트 간 데이터 격리를 위해 각 테스트가 고유한 ID/오너를 생성하는가?
[ ] 단위 테스트가 소스 옆(`_test.go`)에, E2E 테스트가 별도 `test/` 디렉토리에 배치되어 있는가?
[ ] 테스트 함수명이 `TestXxx_When<조건>_Then<기대결과>` 패턴을 따르는가?
    → 예: `TestDepositHandler_Handle_AccountNotFound`, `TestAccount_Withdraw`(서브테스트 이름에 조건/기대결과 명시)
[ ] Aggregate 불변식 위반 테스트와 Domain Event 발행 여부 검증 테스트가 작성되어 있는가?
```

---

## STEP 14 — 전체 일관성 최종 확인

**관련 문서**: [conventions.md](./conventions.md) · [architecture/design-principles.md](architecture/design-principles.md)

```
[ ] 새로 추가한 파일이 필요한 곳(main.go/router.go)에서 생성자로 조립되었는가?
[ ] 새 Command/Query/Result를 추가했다면 Interface DTO가 얇은 매핑 구조체로 함께 추가되었는가?
[ ] 작업한 코드에 TODO, 임시 `fmt.Println`/디버그 출력, 임시 주석이 남아있지 않은가?
[ ] 유비쿼터스 언어가 타입명/메서드명/필드명에 일관되게 반영되어 있는가?
[ ] exported 식별자에 Go doc comment(식별자명으로 시작하는 `//` 주석)가 있는가?
[ ] 비즈니스 로직 설명이 인라인 `//` 주석으로 되어 있는가? (블록 주석 남용 금지)
[ ] 로그 필드가 구조화되고(snake_case) Correlation ID를 포함하는가?
[ ] `gofmt`/`goimports`/`go vet`을 통과하는가?
[ ] 커밋 메시지가 Conventional Commits 형식(feat/fix/refactor + scope)을 따르는가?
[ ] 커밋 메시지의 scope가 서비스 도메인명(account, user 등)인가?
[ ] 커밋 메시지의 description이 한글 서술형이며 끝에 마침표가 없는가?
    → 올바른 예: `feat(account): 계좌 정지 기능 추가`
[ ] 커밋 메시지의 body가 "왜(why)" 변경했는지를 설명하는가?
[ ] BREAKING CHANGE가 있는 경우 footer 또는 type 뒤 `!` 표시로 명시되어 있는가?
[ ] 브랜치명이 Conventional Branch 형식(`<type>/<scope>-<description>`)이고 kebab-case이며 main에서 분기했는가?
[ ] main 브랜치에 직접 commit/push하지 않고 PR을 통해 반영하는가?
[ ] PR 제목이 Conventional Commits 형식과 동일하고, 본문이 Summary + Test plan 형식을 따르는가?
[ ] 머지 전략이 Squash and merge인가?
[ ] 테스트 네이밍이 `TestXxx_When_Then` 패턴을 따르는가?
[ ] `go build ./...` 및 `go test ./...`가 모두 통과하는가?
[ ] `./harness.sh <projectRoot>`가 FAIL 없이 통과하는가?
```

---

## STEP 15 — 설계 산출물 형태 (설계 단계 작업인 경우)

**관련 문서**: [development-process.md](../../../docs/development-process.md) (루트 공용) · [reference.md](./reference.md)

> 설계 단계(RA, SD, DM, TD) 산출물을 작성한 경우에만 적용한다. 이 단계의 산출물 형식은 언어에 종속되지 않으므로 루트 문서를 그대로 따른다.

```
[ ] RA 산출물: 기능 요구사항이 FR-### 번호, 설명, 수용 기준(Acceptance Criteria), 우선순위(MoSCoW)를 포함하는가?
[ ] RA 산출물: 유스케이스가 UC-### 번호, Actor, 선행 조건, 주요 흐름(Happy Path), 예외 흐름, 후행 조건을 포함하는가?
[ ] RA 산출물: 제약 조건 정리표가 기술 스택, 외부 시스템, 일정, 규제, 트래픽 항목을 포함하는가?
[ ] SD 산출물: 서브도메인 분류표가 유형(Core/Supporting/Generic)과 구현 전략을 포함하는가?
[ ] SD 산출물: Bounded Context 정의서가 책임, 핵심 개념, 소속 서브도메인을 포함하는가?
[ ] SD 산출물: Context Map이 관계 유형(Partnership/Shared Kernel/Customer-Supplier/Conformist/ACL/OHS·PL)과 선택 이유를 포함하는가?
[ ] DM 산출물: 이벤트 스토밍 결과 매핑 테이블이 Actor/Command/Aggregate/Domain Event/Policy/External System 열을 포함하는가?
[ ] DM 산출물: 유비쿼터스 언어 용어 사전이 용어(영문)/용어(한글)/정의/소속 Context/비고 열을 포함하는가?
[ ] DM 산출물: 서로 다른 Context에서 같은 단어가 다른 의미로 쓰이는 경우 용어 사전에 명시되어 있는가?
[ ] DM 산출물: Aggregate별 도메인 모델 구조가 Root/Entity 목록/VO 목록/관계를 포함하는가?
[ ] DM 산출물: Domain Event 상세 목록이 이벤트명/발생 조건/포함 데이터/후속 처리(Policy) 열을 포함하는가?
[ ] DM 산출물: 비즈니스 규칙/불변식이 INV-### 번호와 위반 시 처리 방식을 포함하는가?
[ ] TD 산출물: 파일 구조 트리가 `internal/{domain,application,infrastructure,interface}` 4레이어를 포함하는가?
    → NestJS와 달리 Go는 레이어가 최상위, 도메인이 그 하위에 온다([directory-structure.md](architecture/directory-structure.md) 참고)
[ ] TD 산출물: 의존성 조립 계획이 어떤 생성자를 어디서(main.go/router.go) 호출하는지 명시하는가? (DI 컨테이너가 없으므로 provide/useClass 대신 생성자 호출 순서로 표현)
[ ] TD 산출물: Aggregate 설계서가 Root/내부 Entity/내부 VO/외부 참조(ID)/생성 규칙/불변식을 포함하는가?
[ ] TD 산출물: Repository 인터페이스 정의서가 FindByID/FindAll/Save(/필요 시 상태 전환 메서드) 시그니처를 포함하는가?
[ ] TD 산출물: Application Handler 정의서가 유스케이스 매핑/처리 흐름/트랜잭션 범위/실패 시 처리를 포함하는가?
[ ] TD 산출물: Event 흐름도가 동기/비동기 처리 방식과 보상 트랜잭션을 포함하는가?
[ ] IM 산출물: Vertical Slicing(유스케이스 단위 구현)으로 진행하고 있는가?
    → 레이어 단위(수평)가 아닌 유스케이스 단위(수직)로 모든 레이어를 한 번에 구현
[ ] IM 산출물: 슬라이스 계획이 슬라이스 번호/유스케이스/포함 파일/우선순위 형식으로 정리되어 있는가?
```

---

## STEP 16 — 가이드 수정 작업인 경우

**관련 문서**: [development-process.md](../../../docs/development-process.md) (루트 공용) · [conventions.md](./conventions.md)

> 코드 작업이 아니라 가이드 자체(`implementations/go/docs/**`)를 수정하는 경우에만 적용한다.

```
[ ] 새로 추가하거나 수정한 설명이 한글로 작성되어 있는가?
[ ] 새 규칙에 올바른 예시와 잘못된 예시가 함께 작성되어 있는가?
[ ] 작성한 예시가 실제 Go 관용구(에러 반환, context.Context 전파, 컴파일 타임 interface 검증 등)와 모순되지 않는가?
[ ] 문서가 "목표 구현"과 "이 저장소 examples/의 실제 코드"를 명확히 구분해 서술하는가?
    → 이 저장소의 여러 architecture 문서가 알려진 격차(Outbox 미구현, UUID 하이픈 등)를 의도적으로 남겨두고 있다. 새 문서를 쓸 때도 목표와 현재 상태를 혼동하지 않는다
[ ] 작성한 예시가 이 가이드의 다른 규칙(파일 네이밍, import, 에러 처리 등)을 위반하지 않는가?
    → 위반이 있다면 예시를 먼저 수정한 뒤 규칙을 확정
[ ] 가이드 변경 시 main 브랜치가 아닌 새 브랜치에서 PR을 생성하는가?
```

---

## 체크리스트 활용 방법

AI Agent는 작업 완료 후 다음 순서로 자기 검토를 수행한다:

1. **STEP 1~14를 순서대로** 점검한다.
2. 위반 항목 발견 시 **즉시 해당 파일을 수정**하고 체크한다.
3. 수정 후 **연관된 파일(main.go/router.go의 생성자 조립 등)에도 영향이 없는지** 확인한다.
4. 설계 단계 작업이었다면 **STEP 15**도 함께 점검한다.
5. 가이드 수정 작업이었다면 **STEP 16**도 함께 점검한다.
6. 모든 체크 완료 후 `./harness.sh <projectRoot>`로 구조·배치 규칙을 교차 확인하고 작업을 마무리한다.

> 체크리스트는 가이드의 규칙을 요약한 것이다.
> 항목의 의도가 불명확하다면 해당 문서를 참조한다:
> - STEP 1 파일 구조 및 네이밍 → [conventions.md](conventions.md) 섹션 1-3, [directory-structure.md](architecture/directory-structure.md)
> - STEP 2 Domain 레이어 → [tactical-ddd.md](architecture/tactical-ddd.md), [aggregate-id.md](architecture/aggregate-id.md)
> - STEP 3 레이어 아키텍처 / CQRS / 크로스 도메인 → [layer-architecture.md](architecture/layer-architecture.md), [cqrs-pattern.md](architecture/cqrs-pattern.md), [cross-domain.md](architecture/cross-domain.md), [domain-events.md](architecture/domain-events.md)
> - STEP 4 Repository 패턴 → [repository-pattern.md](architecture/repository-pattern.md), [persistence.md](architecture/persistence.md)
> - STEP 5 의존성 조립 → [module-pattern.md](architecture/module-pattern.md), [bootstrap.md](architecture/bootstrap.md)
> - STEP 6 Go 타이핑 → [conventions.md](conventions.md) 섹션 4
> - STEP 7 에러 처리 → [error-handling.md](architecture/error-handling.md)
> - STEP 8 REST API 엔드포인트 → [conventions.md](conventions.md) 섹션 5, [api-response.md](architecture/api-response.md)
> - STEP 9 인증 · 횡단 관심사 · Rate Limiting → [authentication.md](architecture/authentication.md), [cross-cutting-concerns.md](architecture/cross-cutting-concerns.md), [rate-limiting.md](architecture/rate-limiting.md)
> - STEP 10 import → [conventions.md](conventions.md) 섹션 7
> - STEP 11 스케줄링 / Task Queue / Graceful Shutdown → [scheduling.md](architecture/scheduling.md), [graceful-shutdown.md](architecture/graceful-shutdown.md)
> - STEP 12 DB/인프라 → [persistence.md](architecture/persistence.md), [config.md](architecture/config.md), [observability.md](architecture/observability.md)
> - STEP 13 테스트 패턴 → [testing.md](architecture/testing.md)
> - STEP 14 전체 일관성 → 전체 문서 참조
> - STEP 15 설계 산출물 형태 → [development-process.md](../../../docs/development-process.md) (루트 공용)
> - STEP 16 가이드 수정 → [CLAUDE.md](../CLAUDE.md) 가이드 관리 원칙

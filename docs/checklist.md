# 자기 검토 체크리스트

작업 완료 후 아래 체크리스트를 순서대로 검토한다.
각 항목을 점검하여 위반이 발견되면 즉시 수정한 뒤 다음 항목으로 넘어간다.

**검증 규칙**: 각 STEP을 검증할 때 반드시 해당 파일을 실제로 읽고 코드와 대조한다. 코드를 읽지 않고 통과 처리하는 것은 금지한다.

---

## STEP 1 — 파일 구조 및 네이밍

**관련 문서**: [conventions.md](conventions.md) · [architecture/directory-structure.md](architecture/directory-structure.md)

```
[ ] 파일명이 kebab-case가 아닌 것이 있는가?
    → kebab-case로 변경
[ ] DTO 파일명이 동사 우선인가?
    → 올바른 예: get-order-request-param.ts, create-order-request-body.ts
[ ] enum이 다른 파일 내에 인라인으로 선언되어 있는가?
    → <domain>-enum.ts 파일로 분리하여 모듈 루트에 위치
[ ] 상수(const)가 다른 파일 내에 인라인으로 선언되어 있는가?
    → <domain>-constant.ts 파일로 분리하여 모듈 루트에 위치
[ ] Controller 파일명이 <domain>-controller.ts 형식인가?
[ ] Domain 레이어 파일명이 규칙을 따르는가?
    → Aggregate Root: <aggregate-root>.ts, Entity: <entity>.ts, Value Object: <value-object>.ts, Domain Event: <domain-event>.ts
[ ] Repository 인터페이스 파일명이 <aggregate>-repository.ts 형식인가? (domain/ 레이어)
[ ] Query/Result 파일명이 <verb>-<noun>-query.ts / <verb>-<noun>-result.ts 형식인가?
[ ] Adapter 파일명이 규칙을 따르는가?
    → 인터페이스: <external-domain>-adapter.ts (application/adapter/)
    → 구현체: <external-domain>-adapter-impl.ts (infrastructure/)
[ ] 클래스명이 네이밍 규칙을 따르는가?
    → Aggregate Root: 도메인 명사 (Order, User)
    → Value Object: 도메인 개념 (Money, Address)
    → Domain Event: 과거형 (OrderPlaced, OrderCancelled)
    → Repository 인터페이스: <Aggregate>Repository / 구현체: <Aggregate>RepositoryImpl
    → Command: <Verb><Noun>Command / Query: <Verb><Noun>Query / Result: <Verb><Noun>Result
    → ErrorMessage enum: <Domain>ErrorMessage
```

---

## STEP 2 — Domain 레이어

**관련 문서**: [architecture/layer-architecture.md](architecture/layer-architecture.md) · [architecture/tactical-ddd.md](architecture/tactical-ddd.md) · [architecture/domain-service.md](architecture/domain-service.md) · [architecture/aggregate-id.md](architecture/aggregate-id.md)

```
[ ] domain/ 디렉토리에 Aggregate Root, Entity, Value Object, Domain Event, Repository 인터페이스가 있는가?
[ ] Aggregate Root에 비즈니스 규칙과 불변식이 캡슐화되어 있는가?
    → Application Service에 비즈니스 로직이 있다면 Aggregate로 이동
[ ] Domain 레이어 파일에 프레임워크 의존성이 있는가? (@Injectable, @Module 등)
    → 있다면 제거. Domain 레이어는 프레임워크 무의존
[ ] Domain 레이어 파일에 ORM 관련 import가 있는가?
    → 있다면 제거. Infrastructure 레이어에서만 사용
[ ] Repository 인터페이스가 abstract class로 정의되어 있는가?
[ ] Repository 인터페이스가 domain/ 레이어에 위치하는가?
[ ] Aggregate 간 직접 참조 없이 ID 참조만 사용하는가?
[ ] Aggregate 외부에서 내부 상태를 직접 변경하는 코드가 있는가?
    → Aggregate Root의 메서드를 통해 변경하도록 수정
[ ] Value Object에 속성 기반 동등성 비교가 구현되어 있는가?
[ ] Aggregate 생성 시 ID가 UUID v4 (하이픈 제거, 32자리 hex)로 생성되는가?
    → 신규 생성 시 generateId() 사용, DB 복원 시 기존 ID 그대로 사용
```

---

## STEP 3 — 레이어 아키텍처

**관련 문서**: [architecture/layer-architecture.md](architecture/layer-architecture.md) · [architecture/cqrs-pattern.md](architecture/cqrs-pattern.md) · [architecture/cross-domain-communication.md](architecture/cross-domain-communication.md)

```
[ ] Controller가 Service 호출 + 에러 변환 외에 다른 로직을 수행하는가?
    → 있다면 Service로 이동
[ ] Application Service가 비즈니스 로직을 직접 수행하는가? (상태 변경 조건 검사, 계산 등)
    → 있다면 Aggregate의 도메인 메서드로 이동
[ ] Service가 HTTP 예외(HttpException, NotFoundException 등)를 throw하는가?
    → plain Error로 교체. HTTP 변환은 Interface 레이어에서만
[ ] Application 레이어에 쓰기 유스케이스가 있다면 command/ 디렉토리와 Command 객체가 존재하는가?
[ ] Application 레이어에 읽기 유스케이스가 있다면 query/ 디렉토리와 Query/Result 객체가 존재하는가?
[ ] Command Service는 Repository만, Query Service는 Query 인터페이스만 사용하는가?
    → Command Service에서 Query 인터페이스를 직접 사용하거나 역방향 의존이 있다면 수정
[ ] Query 인터페이스가 application/query/에 abstract class로 정의되어 있는가?
[ ] Query 구현체가 infrastructure/에 위치하는가?
[ ] Interface DTO가 Application Query/Result/Command를 extends로 감싸고 있는가?
    → Interface DTO에 추가 로직이나 필드가 있다면 Application 레이어로 이동
[ ] 레이어 의존 방향이 올바른가? (Interface → Application → Domain ← Infrastructure)
    → 하위 레이어가 상위 레이어를 import하는 코드가 있다면 수정
[ ] 이벤트는 Aggregate 내부 도메인 메서드에서만 생성하는가?
    → Command Service가 직접 이벤트를 생성하지 않는다
[ ] Repository 구현체의 save 메서드에서 domainEvents를 outbox에 함께 저장하는가?
[ ] 외부 BC로 알릴 사건은 Integration Event로 변환해 발행하는가?
    → Domain Event 객체를 그대로 외부로 전달하지 않는다
```

---

## STEP 4 — Repository 패턴

**관련 문서**: [architecture/repository-pattern.md](architecture/repository-pattern.md)

```
[ ] Repository가 Aggregate Root 단위로 정의되어 있는가? (테이블/Entity 단위 X)
[ ] Repository 인터페이스(abstract class)가 domain/ 레이어에 있는가?
[ ] Repository 구현체가 infrastructure/ 레이어에 있는가?
[ ] Repository 메서드명이 find<Noun>s / save<Noun> / delete<Noun> 패턴을 따르는가?
[ ] Repository에 update<Noun> 메서드가 있는가?
    → 있다면 제거. 조회 후 Aggregate 도메인 메서드로 수정, save<Noun>으로 저장
[ ] 단건 조회를 위해 별도 findOne / findById 메서드를 만들었는가?
    → 있다면 제거. Service에서 take: 1 + .then(r => r.<noun>s.pop()) 패턴 사용
[ ] Repository find 메서드 반환 타입의 키 이름이 도메인 객체명 복수형인가?
    → 올바른 예: { orders: Order[]; count: number }
    → 잘못된 예: { items: Order[]; count: number }, { result: Order[] }
[ ] Repository 구현체에서 DB 레코드를 도메인 Aggregate 객체로 변환하는가?
    → DB row를 그대로 반환하지 않고 new Aggregate(row)로 변환
```

---

## STEP 5 — 에러 처리

**관련 문서**: [architecture/error-handling.md](architecture/error-handling.md)

```
[ ] Interface 레이어(Controller)에서만 HTTP 예외로 변환하는가?
    → Domain/Application은 plain Error만 throw
[ ] 에러 메시지가 enum으로 타입화되어 있는가?
    → free-form 문자열 직접 throw 금지
[ ] 에러 메시지 enum이 키 = 값 패턴으로 정의되어 있는가?
    → throw new Error(ErrorMessage['...']) 형태로 사용
[ ] 에러 응답 형식이 일관된가? (statusCode, code, message, error 필드 포함)
[ ] Domain/Application에서 throw new HttpException / NotFoundException 등을 사용하는가?
    → 있다면 throw new Error(ErrorMessage['...'])로 교체
```

---

## STEP 6 — REST API 엔드포인트

**관련 문서**: [conventions.md](conventions.md) · [architecture/api-response.md](architecture/api-response.md)

```
[ ] URL이 동사가 아닌 복수 명사 리소스로 구성되어 있는가?
    → 올바른 예: GET /orders, POST /orders
    → 잘못된 예: GET /getOrders, POST /createOrder
[ ] 리소스명이 복수형인가?
    → 올바른 예: /orders, /users / 잘못된 예: /order, /user
[ ] URL이 kebab-case 소문자만 사용하는가?
[ ] HTTP 메서드가 올바르게 사용되는가?
    → GET: 조회, POST: 생성, PUT: 전체 수정, PATCH: 부분 수정, DELETE: 삭제
[ ] 응답 코드가 HTTP 메서드에 맞는가?
    → GET/PUT/PATCH: 200, POST: 201, DELETE: 204
[ ] 비 CRUD 행위가 하위 리소스 경로로 표현되는가?
    → 올바른 예: POST /orders/:orderId/cancel
[ ] 목록 응답 키가 도메인 객체명 복수형인가?
    → 올바른 예: { orders: [...], count: 10 } / 잘못된 예: { data: [...] }
[ ] URL에 후행 슬래시(/)나 파일 확장자(.json)가 없는가?
```

---

## STEP 7 — 트랜잭션 / 멱등성

**관련 문서**: [architecture/layer-architecture.md](architecture/layer-architecture.md) · [architecture/domain-events.md](architecture/domain-events.md)

```
[ ] 여러 Repository에 걸친 쓰기 작업이 하나의 트랜잭션으로 묶여 있는가?
    → Command Service에서 2개 이상의 Repository를 호출하면 트랜잭션 필수
[ ] 단일 Repository만 호출하는 Command에 불필요한 트랜잭션이 없는가?
[ ] Aggregate 저장과 Domain Event outbox 저장이 같은 트랜잭션으로 묶여 있는가?
[ ] Task 적재(enqueue)가 Command 트랜잭션 안에서 이루어지는가? (dual-write 차단)
[ ] 이벤트 핸들러가 멱등하게 구현되어 있는가?
    → at-least-once 전달이므로 중복 수신 시 결과가 동일해야 한다
[ ] Scheduler(@Cron)가 비즈니스 로직을 직접 실행하지 않고 TaskQueue.enqueue만 호출하는가?
[ ] Task Controller가 에러를 그대로 던지는가?
    → catch + 에러 변환 패턴 금지. 예외 삼키면 실패가 소실됨
[ ] DLQ가 모든 Task 큐에 설정되어 있는가?
```

---

## STEP 8 — 관찰 가능성 / 설정

**관련 문서**: [architecture/observability.md](architecture/observability.md) · [architecture/config.md](architecture/config.md)

```
[ ] 로그가 구조화된 형태인가? (key-value JSON, snake_case 필드명)
[ ] Domain 레이어에서 로깅을 수행하지 않는가?
    → 로깅은 Application 레이어 이상에서만
[ ] Correlation ID가 모든 요청에서 전파되는가?
[ ] 기동 시 필수 환경 변수를 검증하고 실패 시 즉시 종료하는가? (Fail-fast)
[ ] 민감 값(DB 비밀번호, JWT secret, API 키)이 코드에 하드코딩되어 있지 않은가?
    → 운영 환경에서는 Secrets Manager 사용
[ ] 설정이 관심사별로 분리된 파일로 관리되는가?
```

---

## STEP 9 — 테스트 패턴

**관련 문서**: [architecture/testing.md](architecture/testing.md)

```
[ ] Domain 레이어 단위 테스트가 프레임워크 없이 순수 코드로 작성되어 있는가?
    → 직접 new Aggregate()로 테스트
[ ] Application Service 테스트에서 Repository를 mock으로 대체하는가?
[ ] E2E/통합 테스트에서 실제 HTTP 요청을 통해 유스케이스 흐름을 검증하는가?
[ ] E2E/통합 테스트에서 in-memory DB 또는 testcontainers를 사용하는가?
    → 운영 DB에 직접 연결하지 않는다
[ ] Aggregate 불변식 위반 테스트가 작성되어 있는가?
[ ] Domain Event 발행 여부를 검증하는 테스트가 있는가?
[ ] 테스트 네이밍이 {도메인행위}_when_{조건}_then_{기대결과} 패턴을 따르는가?
```

---

## STEP 10 — 전체 일관성 최종 확인

**관련 문서**: [conventions.md](conventions.md)

```
[ ] 새로 추가한 파일이 해당 레이어의 등록 구조에 포함되었는가?
[ ] 작업한 코드에서 TODO, console.log, 임시 주석이 남아있지 않은가?
[ ] 유비쿼터스 언어가 코드(클래스명, 메서드명, 변수명)에 일관되게 반영되어 있는가?
[ ] 로거 출력이 구조화된 형태인가? (snake_case 필드명)
[ ] 커밋 메시지가 Conventional Commits 형식을 따르는가? (feat/fix/refactor + scope)
[ ] 커밋 메시지의 description이 서술형이며 끝에 마침표가 없는가?
[ ] 브랜치명이 kebab-case이고 main에서 분기했는가?
[ ] main 브랜치에 직접 commit/push하지 않고 PR을 통해 반영하는가?
```

---

## STEP 11 — 설계 산출물 형태 (설계 단계 작업인 경우)

**관련 문서**: [development-process.md](development-process.md)

> 설계 단계(RA, SD, DM, TD) 산출물을 작성한 경우에만 적용한다.

```
[ ] RA 산출물: 기능 요구사항이 FR-### 번호, 설명, 수용 기준, 우선순위(MoSCoW)를 포함하는가?
[ ] RA 산출물: 유스케이스가 UC-### 번호, Actor, 선행 조건, 주요 흐름, 예외 흐름을 포함하는가?
[ ] SD 산출물: 서브도메인 분류표가 유형(Core/Supporting/Generic)과 구현 전략을 포함하는가?
[ ] SD 산출물: Context Map이 관계 유형과 선택 이유를 포함하는가?
[ ] DM 산출물: 이벤트 스토밍 결과가 Actor/Command/Aggregate/Event/Policy 열을 포함하는가?
[ ] DM 산출물: 유비쿼터스 언어 용어 사전이 용어/정의/소속 Context를 포함하는가?
[ ] DM 산출물: 비즈니스 규칙/불변식이 INV-### 번호와 위반 시 처리 방식을 포함하는가?
[ ] TD 산출물: 파일 구조 트리가 domain/application/infrastructure/interface 4레이어를 포함하는가?
[ ] TD 산출물: Repository 인터페이스 정의서가 find<Noun>s/save<Noun>/delete<Noun>를 포함하는가?
[ ] TD 산출물: Application Service 정의서가 처리 흐름/트랜잭션 범위를 포함하는가?
[ ] IM 산출물: Vertical Slicing(유스케이스 단위 구현)으로 진행하고 있는가?
```

---

## 체크리스트 활용 방법

1. **STEP 1~10을 순서대로** 점검한다.
2. 위반 항목 발견 시 **즉시 해당 파일을 수정**하고 체크한다.
3. 수정 후 **연관된 파일에도 영향이 없는지** 확인한다.
4. 설계 단계 작업이었다면 **STEP 11**도 함께 점검한다.
5. 모든 체크 완료 후 작업을 마무리한다.

> 항목의 의도가 불명확하다면 관련 문서를 참조한다.
> 프레임워크별 추가 검증 항목은 `implementations/<lang>/docs/checklist.md` 참조. (`docs/implementations/`는 루트 원칙과 언어별 문서 간 커버리지 감사 리포트이지 체크리스트 보충 문서가 아니다.)

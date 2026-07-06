# AI Agent 자기 검토 체크리스트

작업 완료 후, 아래 체크리스트를 순서대로 검토한다.
각 항목을 점검하여 위반이 발견되면 즉시 수정한 뒤 다음 항목으로 넘어간다.

### 검증 수행 규칙

- 각 STEP을 검증할 때 반드시 해당 파일을 Read 도구로 읽고 실제 코드와 대조한다.
- 코드를 읽지 않고 통과 처리하는 것은 금지한다.
- 위반 발견 시 즉시 수정한 후 다음 STEP으로 넘어간다.
- 가능하면 `harness/harness.py <projectRoot>`를 실행해 구조적 규칙(파일명, 레이어 배치, domain 순수성, layer-dependency) 위반을 기계적으로 먼저 걸러낸다 — harness는 이 체크리스트의 일부만 자동 검사하므로, 나머지 항목은 이 문서로 직접 검증한다.

---

## STEP 1 — 파일 구조 및 네이밍

**관련 문서**: [conventions.md](./conventions.md) · [architecture/directory-structure.md](./architecture/directory-structure.md)

```
[ ] 파일명이 snake_case.py가 아닌 것이 있는가?
    → 있다면 snake_case.py로 변경 (harness의 file-naming 검사와 동일 기준)
[ ] Handler 파일이 <verb>_<noun>_handler.py 형식이고 application/command/ 또는 application/query/ 에 있는가?
    → 없다면 이동 (harness의 handler-placement 검사와 동일 기준)
[ ] DTO(Pydantic 요청/응답) 클래스명이 동사 우선이 아닌가?
    → 올바른 예: CreateOrderRequest, GetOrderResponse
[ ] 도메인 enum이 domain/ 레이어가 아닌 다른 곳에 인라인으로 선언되어 있는가?
    → 있다면 domain/<concern>_status.py로 분리
[ ] 상수가 매직 넘버/문자열로 여러 파일에 흩어져 있는가?
    → 도메인 패키지 루트의 constants.py로 분리
[ ] Router 파일명이 <domain>_router.py 형식인가?
    → interface/rest/ 아래 위치, 다른 이름이면 변경
[ ] Domain 레이어 파일명이 규칙을 따르는가?
    → Aggregate Root: <aggregate_root>.py, Value Object/Entity: <name>.py, Domain Event: events.py, 예외: errors.py
[ ] Repository 인터페이스가 domain/ 레이어에 있고 ABC로 정의되어 있는가? (repository.py 또는 <aggregate>_repository.py)
[ ] Repository 구현체가 infrastructure/persistence/ 에 있고 클래스명에 구현 기술 접두사(SqlAlchemy 등)가 붙어 있는가?
[ ] Query Result가 application/query/result.py에 dataclass로 정의되어 있는가?
[ ] Adapter 파일명이 규칙을 따르는가?
    → 인터페이스: <external_domain>_adapter.py (application/adapter/)
    → 구현체: <provider>_<external_domain>_adapter.py (infrastructure/)
[ ] 기술 인프라 Service(Technical Service) 파일명이 규칙을 따르는가?
    → 인터페이스: <concern>_service.py (application/service/)
    → 구현체: <provider>_<concern>_service.py (infrastructure/<concern>/)
[ ] 설정 클래스 파일명이 <concern>_config.py 형식이고 config/ 디렉토리에 위치하는가?
[ ] 설정 검증 파일이 validator.py 형식이고 config/ 디렉토리에 위치하는가?
[ ] 클래스명이 네이밍 규칙을 따르는가?
    → Aggregate Root: 도메인 명사 (Order, Account)
    → Value Object/Entity: 도메인 개념 (Money, OrderItem)
    → Domain Event: 과거형 (OrderPlaced, OrderCancelled)
    → Repository 인터페이스: <Aggregate>Repository / 구현체: SqlAlchemy<Aggregate>Repository
    → Adapter 인터페이스: <ExternalDomain>Adapter / 구현체: <Provider><ExternalDomain>Adapter
    → Command: <Verb><Noun>Command / Query: <Verb><Noun>Query / Result: <Verb><Noun>Result
    → CommandHandler/QueryHandler: <Verb><Noun>Handler
    → 도메인 예외: <Domain 개념><상황>Error / 에러 코드 enum: <Domain>ErrorCode
```

---

## STEP 2 — Domain 레이어

**관련 문서**: [architecture/layer-architecture.md](./architecture/layer-architecture.md) · [architecture/tactical-ddd.md](./architecture/tactical-ddd.md) · [domain-service.md](../../../docs/architecture/domain-service.md) (루트 공용) · [architecture/aggregate-id.md](./architecture/aggregate-id.md)

```
[ ] domain/ 디렉토리에 Aggregate Root, Entity, Value Object, Domain Event, Repository 인터페이스가 있는가?
[ ] Aggregate Root에 비즈니스 규칙과 불변식이 캡슐화되어 있는가?
    → Application Handler에 비즈니스 로직이 있다면 Aggregate로 이동
[ ] Aggregate Root가 @dataclass로 선언되어 있는가?
    → 있다면 일반 클래스(__init__ + 메서드)로 변경. dataclass 자동 생성자는 불변식 보호에 부적합
[ ] Entity/Value Object/Domain Event가 @dataclass(frozen=True)로 선언되어 있는가?
[ ] Domain 레이어 파일에 fastapi/sqlalchemy/aioboto3 등 외부 라이브러리 import가 있는가?
    → 있다면 제거. Domain 레이어는 표준 라이브러리(dataclasses, abc, datetime, enum)만 사용 (harness의 domain-purity 검사와 동일 기준)
[ ] Repository 인터페이스가 ABC + @abstractmethod로 정의되어 있는가?
[ ] Repository 인터페이스가 domain/ 레이어에 위치하는가? (harness의 repository-abc 검사와 동일 기준)
[ ] Aggregate 간 직접 참조 없이 ID 참조만 사용하는가?
[ ] Aggregate 외부에서 내부 상태를 직접 변경하는 코드가 있는가?
    → 있다면 Aggregate Root의 메서드를 통해 변경하도록 수정
[ ] Value Object가 frozen dataclass의 자동 생성 __eq__(속성 기반 동등성)를 그대로 사용하는가?
    → 별도 equals() 메서드를 만들지 않는다
[ ] Aggregate 생성 시 ID가 uuid.uuid4().hex(하이픈 제거, 32자리 hex 문자열)로 생성되는가?
    → str(uuid.uuid4())(하이픈 포함)를 쓰고 있다면 .hex로 교체
[ ] ID 생성이 Aggregate의 팩토리 classmethod(Order.create() 등) 내부에서 이루어지는가?
    → DB 복원 시(Repository의 _to_domain())에는 새 ID를 생성하지 않고 기존 값을 그대로 사용하는가?
[ ] Domain 레이어에서 로깅(logging, contextvars 등)을 사용하고 있지 않은가?
    → Domain 레이어는 프레임워크뿐 아니라 횡단 관심사에도 무의존
```

---

## STEP 3 — 레이어 아키텍처 / CQRS / 이벤트

**관련 문서**: [architecture/layer-architecture.md](./architecture/layer-architecture.md) · [architecture/cqrs-pattern.md](./architecture/cqrs-pattern.md) · [architecture/domain-events.md](./architecture/domain-events.md) · [architecture/cross-domain.md](./architecture/cross-domain.md)

```
[ ] 라우트 함수가 Handler 생성 + execute() 호출 + 응답 변환 외에 다른 로직을 수행하는가?
    → 있다면 Handler로 이동
[ ] Handler가 비즈니스 로직을 직접 수행하는가? (상태 변경 조건 검사, 계산 등)
    → 있다면 Aggregate의 도메인 메서드로 이동
[ ] Handler가 HTTPException 등 HTTP 관련 예외를 raise하는가?
    → 있다면 domain/errors.py의 plain Exception 하위 클래스로 교체
[ ] Handler가 SQLAlchemy 세션/쿼리를 직접 사용하는가?
    → 있다면 Repository 구현체로 이동
[ ] Repository 구현체가 비즈니스 로직을 포함하는가?
    → 있다면 Aggregate 또는 Handler로 이동
[ ] Handler 클래스의 멤버 구성 순서가 (1) __init__ → (2) execute() → (3) private 헬퍼인가?
[ ] Application 레이어의 디렉토리 구조가 directory-structure.md와 일치하는가? (command/, query/ 등)
    → 쓰기 유스케이스가 있다면 command/ 디렉토리와 Command dataclass + Handler가 존재해야 한다
    → 읽기 유스케이스가 있다면 query/ 디렉토리와 Query dataclass + Result + Handler가 존재해야 한다
[ ] Command Handler와 Query Handler가 물리적으로 분리되어 있는가? (application/command/ vs application/query/, harness의 handler-placement 검사와 동일 기준)
[ ] Query Handler가 도메인 Aggregate를 직접 반환하는가?
    → 있다면 application/query/result.py의 Result dataclass로 변환 후 반환
[ ] 라우트 함수(Interface DTO)가 Pydantic 검증 이상의 로직이나 필드를 가지는가?
    → 있다면 Application Command/Query/Result로 이동
[ ] 레이어 의존 방향이 올바른가? (interface → application → domain ← infrastructure)
    → application/ 이 infrastructure/ 의 구체 클래스를 직접 import하고 있다면 수정 (harness의 layer-dependency 검사와 동일 기준)
[ ] 라우트 함수가 모두 async def이며 반환 타입(response_model과 동일한 타입)이 명시되어 있는가?
[ ] Aggregate 내부의 하위 Entity를 Aggregate Root의 Repository를 통해 함께 저장/조회하는가?
    → 하위 Entity에 별도 Repository를 만들지 않는다
[ ] 이벤트는 Aggregate 내부 도메인 메서드에서만 생성하는가? (self._events.append(...))
    → Handler가 직접 이벤트 객체를 생성하지 않는다
[ ] Repository 구현체의 save 메서드에서 Aggregate가 pull_events()로 꺼낸 이벤트를 Outbox 테이블에 같은 트랜잭션으로 저장하는가?
    → Handler가 Technical Service(NotificationService 등)를 직접 호출해 이벤트를 처리하고 있다면, Outbox 경유로 전환 검토 (domain-events.md의 "알려진 격차" 참조)
[ ] Repository 구현체가 이벤트를 Outbox에 저장한 후 aggregate.pull_events()로 버퍼를 비우는가? (중복 저장 방지)
[ ] Outbox 폴링/릴레이가 실패해도 처리 완료 표시(processed=True)를 남기지 않아 다음 주기에 재시도되는가? (at-least-once)
[ ] 이벤트 후속 처리(알림 발송 등)가 멱등하게 구현되어 있는가?
    → 이미 처리된 이벤트인지 Ledger 테이블로 확인 후 처리, 또는 DB unique 제약으로 중복 방지
[ ] 크로스 도메인 호출이 필요한 경우, 호출하는 쪽의 application/adapter/에 ABC를 정의하고 infrastructure/에 구현체를 두었는가?
    → Application Handler가 다른 도메인의 Repository/Handler를 직접 import하고 있다면 Adapter로 교체
[ ] Adapter 인터페이스가 호출하는 쪽에 필요한 메서드만 정의하고, 대상 도메인의 전체 API를 노출하지 않는가?
[ ] Adapter가 대상 도메인의 내부 모델을 그대로 반환하지 않고, 호출하는 쪽 전용 DTO(dataclass)로 변환해 반환하는가?
```

---

## STEP 4 — Repository 패턴

**관련 문서**: [architecture/repository-pattern.md](./architecture/repository-pattern.md) · [architecture/persistence.md](./architecture/persistence.md)

```
[ ] Repository가 Aggregate Root 단위로 정의되어 있는가? (Entity/테이블 단위 X, harness의 repository-abc/repository-impl 검사와 동일 기준)
[ ] Repository 인터페이스(ABC)가 domain/ 레이어에 있는가?
[ ] Repository 구현체가 infrastructure/persistence/ 레이어에 있는가?
[ ] Repository 구현체 클래스명이 SqlAlchemy<Aggregate>Repository 인가?
[ ] Repository 조회 메서드명이 find_<noun>s 패턴(단건/목록 통일)을 따르는가?
    → find_by_id와 find_all로 분리되어 있다면, 새로 작성하는 Repository는 find_<noun>s(take, page, ...) 하나로 통일하고 단건은 take=1 + 인덱싱으로 조회 (repository-pattern.md의 "알려진 격차"와 "root 컨벤션에 맞춘 형태" 참조)
[ ] Repository에 update_<noun> 메서드가 있는가?
    → 있다면 제거. 조회 후 Aggregate 도메인 메서드로 수정, save() 하나로 upsert
[ ] save()가 신규/기존을 판별해 upsert처럼 동작하는가? (session.get()으로 기존 row 조회 후 분기)
[ ] Handler가 save/delete 내부 cascade 순서를 직접 관리하는가?
    → 있다면 해당 cascade 로직을 Repository 구현체 내부로 이동 (하위 Entity도 함께 save)
[ ] Repository 구현체가 DB 모델(SQLAlchemy Model)을 도메인 Aggregate 객체로 변환하고 있는가?
    → DB row를 그대로 반환하지 않고 Aggregate(...)로 변환하는 _to_domain() 등을 거치는가?
[ ] Repository find 메서드 반환 타입의 키/필드 이름이 도메인 객체명 복수형인가?
    → 올바른 예: tuple[list[Order], int] 또는 { "orders": [...], "count": ... }
    → 잘못된 예: { "items": [...], "count": ... }, { "result": [...] }
[ ] 동적 필터 조건이 값이 있을 때만 적용되는가? (if account_id: stmt = stmt.where(...))
[ ] count 쿼리가 필터 조건을 본 쿼리와 동일하게 적용하는가?
```

---

## STEP 5 — DI(`Depends`) / 모듈 경계 / 크로스 도메인 / 인프라 서비스

**관련 문서**: [architecture/module-pattern.md](./architecture/module-pattern.md) · [architecture/shared-modules.md](./architecture/shared-modules.md) · [architecture/bootstrap.md](./architecture/bootstrap.md) · [architecture/cross-domain.md](./architecture/cross-domain.md)

```
[ ] 도메인이 src/<domain>/ 단위 패키지로 구성되어 있는가?
    → 레이어 단위(routers 패키지, services 패키지)로 나누지 않는다 (harness의 directory-structure 검사와 동일 기준)
[ ] 하나의 도메인 패키지 안에 domain/application/interface/infrastructure 4개 레이어가 모두 포함되어 있는가?
[ ] Repository/Adapter/Technical Service의 ABC ↔ 구현체 바인딩이 interface/rest/<domain>_router.py의 Depends 팩토리 함수로 이루어지는가?
    → NestJS의 { provide, useClass }에 대응하는 지점은 팩토리 함수 자체다. 라우트 함수 안에서 구현체를 직접 인스턴스화하고 있다면 팩토리 함수로 분리
[ ] 다른 도메인의 Repository/Handler를 Application Handler에서 직접 import하고 있는가?
    → 있다면 Adapter 패턴으로 변경: application/adapter/에 ABC, infrastructure/에 구현체
[ ] 두 도메인이 서로 top-level에서 import해 순환 import가 발생하는가?
    → 먼저 Bounded Context 경계를 재검토한다. 타입 힌트만 필요하면 TYPE_CHECKING, 런타임에 필요하면 함수 내부 지연 import로 해소
[ ] 암복호화·파일 스토리지·외부 API 클라이언트 등 기술 인프라를 Application Handler에서 직접 구현하고 있는가?
    → 있다면 Technical Service 패턴으로 변경: application/service/에 ABC, infrastructure/<concern>/에 구현체
[ ] Technical Service 인터페이스가 application/service/에 ABC로 정의되어 있는가?
[ ] Technical Service 구현체가 infrastructure/<concern>/에 위치하고 Depends 팩토리로 바인딩되는가?
[ ] 파일 업로드/다운로드 시 서버가 파일 바이너리를 직접 처리하는가?
    → 있다면 Presigned URL 패턴으로 변경. 서버는 URL 발급만, 클라이언트↔스토리지 직접 통신
[ ] 파일 소유 Entity에 file_key(고정 길이)와 extension 컬럼이 있는가?
    → DB에는 메타데이터만 저장, 파일 자체는 스토리지에 저장
[ ] StorageService가 Technical Service 패턴(application/service/에 ABC, infrastructure/storage/에 구현체)으로 분리되어 있는가?
[ ] main.py의 lifespan이 기동(fail-fast 검증, 스키마 생성/마이그레이션)과 종료(엔진 dispose, in-flight 요청 대기)를 모두 처리하는가?
[ ] main.py에 GET /health/live, GET /health/ready 헬스체크 라우트가 있는가?
    → liveness는 항상 200, readiness는 종료 중 503
[ ] pydantic_settings.BaseSettings 기반 설정 클래스가 관심사별로 분리되어 있는가? (config/database_config.py, config/jwt_config.py 등)
[ ] 필수 환경 변수가 BaseSettings 인스턴스화 시점에 검증되고, 실패 시 sys.exit(1)로 앱 기동을 중단하는가?
[ ] DB 비밀번호·JWT 시크릿·외부 API 키 등 민감 값을 운영 환경에서 AWS Secrets Manager로 조회하는가?
    → 환경 변수에 직접 하드코딩 금지. SecretService(Technical Service 패턴)를 통해 TTL 캐시와 함께 조회
```

---

## STEP 6 — 타이핑 패턴

**관련 문서**: [conventions.md](./conventions.md) 섹션 4

```
[ ] Aggregate Root가 @dataclass가 아닌 일반 클래스(__init__ + 메서드)로 작성되어 있는가?
[ ] Entity/Value Object/Domain Event가 @dataclass(frozen=True)로 작성되어 있는가?
[ ] Command/Query가 @dataclass로 정의되어 있는가?
[ ] typing.Any를 사용한 곳이 있는가?
    → 있다면 구체적인 타입 또는 Union/제네릭으로 교체
[ ] DB에서 오는 nullable 필드가 T | None 형태인가?
[ ] 도메인 상태값(status 등)이 str 대신 enum 또는 Literal 타입으로 정의되어 있는가?
[ ] Handler execute() 메서드의 반환 타입이 명시되어 있는가?
[ ] 라우트 함수의 반환 타입이 response_model과 동일하게 명시되어 있는가?
[ ] datetime 저장/조회 방식이 이 프로젝트 내에서 일관되는가? (UTC naive 또는 타임존 인식 aware 중 하나로 통일, 혼용 금지)
[ ] 복잡한 Union 타입에 type alias를 사용하고 있는가?
    → 올바른 예: OrderDomainEvent = Union[OrderPlaced, OrderCancelled]
```

---

## STEP 7 — 에러 처리

**관련 문서**: [architecture/error-handling.md](./architecture/error-handling.md)

```
[ ] Domain/Application 레이어가 HTTPException 등 HTTP 관련 예외를 raise하는가?
    → 있다면 domain/errors.py의 plain Exception 하위 클래스로 교체
[ ] 도메인 예외가 domain/errors.py에 클래스 계층(<Domain>Error 상위 + 구체 하위 클래스)으로 정의되어 있는가?
    → free-form 문자열을 직접 raise하지 않는다
[ ] 각 도메인 예외가 error_codes.py의 <Domain>ErrorCode 값을 code 속성으로 갖는가?
[ ] <Domain>ErrorCode의 모든 항목이 SCREAMING_SNAKE_CASE 고정 문자열 값을 가지는가?
[ ] 도메인 예외와 에러 코드가 1:1로 존재하는가?
    → 예외만 있고 코드가 없거나 반대 상황이 발생하지 않도록 한다
[ ] main.py의 @app.exception_handler가 유일한 HTTP 변환 지점인가?
    → 라우트 함수/Handler 안에서 개별적으로 예외를 잡아 HTTP 응답을 만들고 있다면 제거
[ ] 구체적인 예외 타입(OrderNotFoundError)이 상위 타입(OrderError)보다 먼저 @app.exception_handler로 등록되어 있는가?
[ ] 에러 응답 body가 statusCode, code, message, error 4개 필드 형식을 따르는가?
    → { "message": "..." }뿐이라면 build_error_response() 등으로 표준화
[ ] Pydantic 검증 실패(422) 응답도 동일한 4필드 형식이며 code가 VALIDATION_FAILED로 고정되어 있는가?
[ ] 매핑되지 않은 예외가 그대로 500으로 노출되며, 스택 트레이스가 응답 본문에 포함되지 않는가?
```

---

## STEP 8 — REST API 엔드포인트

**관련 문서**: [architecture/api-response.md](./architecture/api-response.md) · [architecture/rate-limiting.md](./architecture/rate-limiting.md) · [architecture/authentication.md](./architecture/authentication.md) · [architecture/cross-cutting-concerns.md](./architecture/cross-cutting-concerns.md)

```
[ ] URL이 동사가 아닌 복수 명사 리소스로 구성되어 있는가?
    → 올바른 예: GET /orders, POST /orders
    → 잘못된 예: GET /get-orders, POST /create-order
[ ] 리소스명이 복수형인가?
[ ] URL이 kebab-case 소문자만 사용하는가? (Python 코드의 snake_case와 별개)
[ ] HTTP 메서드가 올바르게 사용되는가? (GET 조회, POST 생성, PUT 전체 수정, PATCH 부분 수정, DELETE 삭제)
[ ] 비 CRUD 행위가 하위 리소스 경로로 표현되는가?
    → 올바른 예: POST /orders/{order_id}/cancel
[ ] 중첩 리소스가 2단계 이내인가?
[ ] 라우트 데코레이터의 status_code가 HTTP 메서드에 맞게 명시되어 있는가?
    → GET/PUT/PATCH: 200(기본값), POST: 201, DELETE: 204
[ ] URL에 후행 슬래시(/)나 파일 확장자(.json)가 없는가?
[ ] 목록 조회 응답의 필드명이 도메인 객체명 복수형(orders, count)인가?
    → result, data, items 같은 범용 필드명 금지
[ ] 응답이 범용 래퍼({"success": true, "data": {...}})로 감싸여 있지 않은가?
    → Pydantic 모델을 최상위 JSON으로 직렬화, 에러/정상 구분은 HTTP 상태 코드로
[ ] 페이지네이션이 page(0부터), take로 통일되어 있는가?
[ ] 인증이 필요한 라우터에 APIRouter(dependencies=[Depends(get_current_user)])가 적용되어 있는가?
    → 개별 라우트마다 반복 선언하지 않고 라우터 레벨에서 일괄 적용
[ ] get_current_user가 fastapi.security.HTTPBearer + JWT 검증을 거치는가?
    → X-User-Id 같은 헤더를 검증 없이 신뢰하고 있다면 authentication.md의 JWT 패턴으로 교체
[ ] JWT payload에 user_id 등 최소한의 정보만 담겨 있는가?
    → email, role, permissions 등 자주 변하거나 민감한 정보를 담지 않는다
[ ] Rate Limiting이 slowapi 등으로 전역 등록되어 있는가? (default_limits)
[ ] 쓰기(POST/PUT/PATCH/DELETE) 라우트의 제한이 조회(GET)보다 엄격한가?
[ ] 헬스체크(/health/*) 엔드포인트가 과도한 제한에 걸리지 않는가?
```

---

## STEP 9 — Pydantic 응답 모델 / OpenAPI 문서화

**관련 문서**: [conventions.md](./conventions.md) 섹션 8

```
[ ] 모든 라우트 함수에 response_model(또는 없음이 명확한 204 라우트)이 명시되어 있는가?
[ ] 라우트 함수 반환 타입 힌트가 response_model과 동일한가?
[ ] 요청 Pydantic 모델 필드에 Field(..., min_length=, max_length=, description=)가 적절히 적용되어 있는가?
[ ] Optional 필드가 T | None = Field(None, description=...) 형태인가?
[ ] 배열/nullable 필드가 타입 힌트(list[...], T | None)만으로 표현되고 별도 데코레이터가 필요하지 않은가?
[ ] Application Result(dataclass)가 아닌 Interface Pydantic 모델(schemas.py)에만 Field/검증이 작성되어 있는가?
[ ] 사용 중단 예정 엔드포인트에 deprecated=True가 표시되어 있는가?
    → 즉시 삭제하지 않고 deprecated 표시 후 마이그레이션 기간 확보
[ ] /docs, /redoc, /openapi.json이 별도 설정 없이 정상 노출되는가? (FastAPI(title=...)만으로 충분)
```

---

## STEP 10 — import 구성

**관련 문서**: [conventions.md](./conventions.md) 섹션 7

```
[ ] import가 2그룹(표준 라이브러리/서드파티 → 빈 줄 → 내부 모듈)으로 구성되어 있는가?
[ ] 같은 도메인 패키지 내부에서 상대 임포트를 일관되게 사용하는가?
[ ] 다른 최상위 패키지(src.database, src.common, 다른 도메인)를 절대 임포트로 참조하고 있는가?
[ ] __init__.py에서 하위 모듈을 재수출(re-export)하고 있지 않은가?
    → import 경로가 이원화되지 않도록 __init__.py는 비워두거나 패키지 마커로만 사용
[ ] 순환 import 해소에 TYPE_CHECKING 또는 함수 내부 지연 import를 사용하는가?
    → top-level import끼리 서로를 가리키는 순환이 남아있지 않은가?
```

---

## STEP 11 — 라우터 구성 / 인증 / 스케줄링 / Graceful Shutdown

**관련 문서**: [architecture/module-pattern.md](./architecture/module-pattern.md) · [architecture/scheduling.md](./architecture/scheduling.md) · [architecture/graceful-shutdown.md](./architecture/graceful-shutdown.md) · [architecture/domain-events.md](./architecture/domain-events.md) · [architecture/authentication.md](./architecture/authentication.md)

```
[ ] 도메인별 APIRouter가 prefix와 tags를 갖고 있는가? (APIRouter(prefix="/orders", tags=["Order"]))
[ ] main.py가 app.include_router(...)로 각 도메인 라우터를 조합하고 있는가?
[ ] 인증이 필요한 라우터에 공통 Depends가 걸려 있고, 인증이 불필요한 라우터(/auth/sign-in, /health/*)는 제외되어 있는가?
[ ] Domain 레이어가 인증 컨텍스트(현재 사용자 등)에 의존하지 않는가?
    → requester_id 등은 Interface 레이어에서 검증된 값으로 Command/Query에 실어 전달
[ ] Scheduler(APScheduler 등)가 infrastructure/scheduling/에 위치하는가?
    → Application/Domain 레이어에 스케줄링 코드를 직접 두지 않는다
[ ] Scheduler가 비즈니스 로직을 직접 실행하지 않고, Application Handler 호출 또는 Outbox 릴레이만 수행하는가?
[ ] Scheduler의 job 함수가 try-except + logger.exception으로 실패를 명시적으로 로깅하는가?
    → APScheduler는 job 내부 예외를 조용히 삼키므로 직접 로깅하지 않으면 실패가 관찰 불가
[ ] 여러 인스턴스가 동시에 실행되면 안 되는 배치에 DB row 잠금 또는 분산 락이 적용되어 있는가?
[ ] main.py의 lifespan에서 scheduler.shutdown(wait=True)로 진행 중인 job 완료를 대기한 뒤 종료하는가?
[ ] app.state["is_shutting_down"] 같은 플래그가 리소스 정리(engine.dispose() 등)보다 먼저 설정되는가?
    → readiness 프로브가 종료 중임을 먼저 감지하도록
[ ] GET /health/live가 종료 중에도 항상 200을 반환하는가?
```

---

## STEP 12 — DB / 인프라 패턴

**관련 문서**: [architecture/persistence.md](./architecture/persistence.md) · [architecture/config.md](./architecture/config.md) · [architecture/secret-manager.md](./architecture/secret-manager.md) · [architecture/local-dev.md](./architecture/local-dev.md) · [architecture/container.md](./architecture/container.md) · [architecture/observability.md](./architecture/observability.md)

```
[ ] SQLAlchemy 모델이 created_at, updated_at, deleted_at(nullable) 컬럼을 포함하는가?
    → 불변 기록(거래 내역 등)은 updated_at/deleted_at을 생략해도 무방
[ ] 삭제 시 session.delete()가 아닌 deleted_at = datetime.utcnow() 설정(soft delete)을 사용하는가?
    → hard delete 사용 금지
[ ] 조회 쿼리가 모두 deleted_at.is_(None) 조건을 포함하는가?
[ ] 하나의 HTTP 요청 = 하나의 AsyncSession(Depends(get_session)의 요청 스코프 캐싱)으로 트랜잭션 경계가 관리되는가?
[ ] 여러 Repository/Technical Service에 걸친 쓰기가 같은 세션(같은 Depends(get_session) 인스턴스)으로 조립되는가?
[ ] 프로덕션 기동 경로에서 Base.metadata.create_all()을 호출하고 있는가?
    → 있다면 제거하고 Alembic 마이그레이션(alembic upgrade head)으로 대체. create_all은 로컬/테스트 전용
[ ] 스키마 변경 후 Alembic 마이그레이션 파일을 생성했는가? (alembic revision --autogenerate)
[ ] Repository의 동적 필터 조건이 값이 있을 때만 적용되는가?
[ ] Repository find 메서드가 단건/목록 조회를 find_<noun>s 하나로 통일하고 있는가? (STEP 4와 동일 기준)
[ ] DB 비밀번호·JWT 시크릿·외부 API 키 등 민감 값이 운영 환경에서 AWS Secrets Manager로 조회되는가?
    → 환경 변수에 하드코딩된 기본값("test" 등)이 운영에서도 그대로 쓰이지 않는가?
[ ] SecretService에 TTL 캐시가 적용되어 반복 조회를 피하는가?
[ ] pydantic_settings.BaseSettings 인스턴스화 시점에 fail-fast 검증이 이루어지는가?
[ ] 로컬 개발이 docker-compose.yml(Postgres + LocalStack)로 격리되어 있는가?
[ ] 모든 인프라 서비스에 healthcheck가 설정되어 있는가?
[ ] Dockerfile이 멀티스테이지 빌드이고, 최종 이미지에 빌드 도구(gcc 등)가 포함되지 않는가?
[ ] Dockerfile의 CMD가 exec form(["uvicorn", ...])인가?
    → shell form은 SIGTERM 전달을 지연시켜 graceful shutdown이 동작하지 않는다
[ ] non-root 사용자로 컨테이너를 실행하는가?
[ ] 로그가 구조화된 JSON 형태이고 필드명이 snake_case인가? (extra=로 구조화 필드 전달)
[ ] Correlation ID가 contextvars 기반 미들웨어로 모든 요청에 주입되고, 모든 로그 라인에 자동 포함되는가?
[ ] Domain 레이어에서 로깅을 수행하지 않는가? (STEP 2와 동일 기준)
```

---

## STEP 13 — 테스트 패턴

**관련 문서**: [architecture/testing.md](./architecture/testing.md)

```
[ ] Domain 레이어 단위 테스트가 프레임워크·mock 없이 Aggregate를 직접 인스턴스화해서 작성되어 있는가?
[ ] Application Handler 테스트에서 Repository/Adapter/Technical Service를 unittest.mock.AsyncMock으로 대체하고 있는가?
[ ] E2E 테스트에서 httpx.ASGITransport + app.dependency_overrides로 실제 요청 흐름을 검증하는가?
[ ] E2E/통합 테스트에서 testcontainers(Postgres, 필요 시 LocalStack)를 사용하는가?
    → 운영 DB/AWS 서비스에 직접 연결하지 않는다
[ ] Aggregate 불변식 위반 테스트가 작성되어 있는가? (pytest.raises(SpecificError))
[ ] Domain Event 수집(pull_events()) 여부를 검증하는 테스트가 있는가?
[ ] 에러 검증이 pytest.raises(SpecificErrorClass)로 타입 기준인가? (문자열 메시지 비교 금지)
[ ] Domain/Application 단위 테스트가 tests/unit/domain/, tests/unit/application/ 등 일관된 위치에 배치되어 있는가?
[ ] E2E 테스트가 tests/ 디렉토리에 test_<domain>_e2e.py로 배치되어 있는가?
[ ] pytest.ini의 asyncio_mode 설정이 프로젝트 전체에서 일관되게 적용되는가?
[ ] 테스트 네이밍이 test_<메서드>_<조건>_<기대결과> 패턴(한글 서술형 또는 영문 스타일 중 파일 내 일관된 하나)을 따르는가?
```

---

## STEP 14 — 전체 일관성 최종 확인

**관련 문서**: [conventions.md](./conventions.md) · [architecture/design-principles.md](./architecture/design-principles.md)

```
[ ] main.py의 @app.exception_handler에서 발생 가능한 모든 도메인 예외가 매핑되어 있는가?
    → 누락된 예외가 있다면 핸들러 추가
[ ] 새로 추가한 Handler/Repository/Adapter/Technical Service가 Depends 팩토리로 실제 배선되어 있는가?
    → 정의만 되어 있고 어떤 라우트에서도 Depends로 참조되지 않는 죽은 코드가 없는가?
[ ] Command/Query/Result를 새로 만들었다면 Interface Pydantic 모델(schemas.py)이 이를 감싸는 얇은 변환만 하고 있는가?
[ ] 작업한 코드에 TODO, print(), 임시 주석이 남아있지 않은가?
[ ] 유비쿼터스 언어가 코드(클래스명, 함수명, 변수명)에 일관되게 반영되어 있는가?
[ ] 주석 스타일이 # 인라인 주석 위주이고, docstring은 공개 API에 한 줄 요약 정도로만 쓰였는가?
[ ] 로거 출력이 구조화된 형태인가? (extra=, snake_case 필드명)
[ ] 커밋 메시지가 Conventional Commits 형식(feat/fix/refactor + scope)을 따르는가?
[ ] 커밋 메시지의 scope가 서비스 도메인명(order, account, payment 등)인가?
    → 여러 도메인에 걸친 변경이면 scope 생략 또는 상위 개념 사용
[ ] 커밋 메시지의 description이 한글·서술형이며 끝에 마침표가 없는가?
[ ] 커밋 메시지의 body가 "왜(why)" 변경했는지를 설명하는가?
[ ] BREAKING CHANGE가 있는 경우 footer 또는 type 뒤 ! 표시로 명시되어 있는가?
[ ] 브랜치명이 Conventional Branch 형식(<type>/<scope>-<description>)을 따르고 모든 단어가 kebab-case인가?
[ ] main 브랜치에 직접 commit/push하지 않고 PR을 통해 반영하는가?
[ ] PR 제목이 Conventional Commits 형식과 동일하고, 본문이 Summary + Test plan 형식을 따르는가?
[ ] 머지 전략이 Squash and merge인가?
[ ] harness/harness.py를 실행했을 때 FAIL 항목이 없는가?
```

---

## STEP 15 — 설계 산출물 형태 (설계 단계 작업인 경우)

**관련 문서**: [development-process.md](../../../docs/development-process.md) (루트 공용) · [reference.md](./reference.md)

> 설계 단계(RA, SD, DM, TD) 산출물을 작성한 경우에만 적용한다. 이 STEP은 언어 무관 산출물 형식이므로 NestJS 구현과 동일한 기준을 따른다.

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
[ ] TD 산출물: 파일 구조 트리가 domain/application/infrastructure/interface 4레이어를 포함하는가?
[ ] TD 산출물: Depends 바인딩 구성이 ABC ↔ 구현체 매핑을 명시하는가? (팩토리 함수 단위)
[ ] TD 산출물: Aggregate 설계서가 Root/내부 Entity/내부 VO/외부 참조(ID)/생성 규칙/불변식을 포함하는가?
[ ] TD 산출물: Repository 인터페이스 정의서가 find_<noun>s/save/delete 메서드를 포함하는가?
[ ] TD 산출물: Application Handler 정의서가 유스케이스 매핑/처리 흐름/트랜잭션 범위/실패 시 처리를 포함하는가?
[ ] TD 산출물: Event 흐름도가 동기/비동기 처리 방식과 보상 트랜잭션을 포함하는가?
[ ] IM 산출물: Vertical Slicing(유스케이스 단위 구현)으로 진행하고 있는가?
    → 레이어 단위(수평)가 아닌 유스케이스 단위(수직)로 모든 레이어를 한 번에 구현
[ ] IM 산출물: 슬라이스 계획이 슬라이스 번호/유스케이스/포함 파일/우선순위 형식으로 정리되어 있는가?
```

---

## STEP 16 — 가이드 수정 작업인 경우

**관련 문서**: [development-process.md](../../../docs/development-process.md) (루트 공용) · [conventions.md](./conventions.md)

> 코드 작업이 아니라 가이드 자체를 수정하는 경우에만 적용한다.

```
[ ] 새로 추가하거나 수정한 설명이 한글로 작성되어 있는가?
[ ] 새 규칙에 올바른 예시(# 올바른 방식)와 잘못된 예시(# 잘못된 방식)가 함께 작성되어 있는가?
[ ] 작성한 예시가 이 가이드의 다른 규칙(파일 네이밍, import, 타이핑, Pydantic 문서화 등)을 위반하지 않는가?
    → 위반이 있다면 예시를 먼저 수정한 뒤 규칙을 확정
[ ] 새 규칙이 이 저장소의 examples/ 코드에 실제로 존재하는 "알려진 격차"와 모순되지 않는가?
    → architecture/*.md가 이미 격차로 명시한 부분이라면 격차 설명을 유지하고, 이 문서는 목표 상태만 기술
[ ] 가이드 변경 시 main 브랜치가 아닌 새 브랜치에서 PR을 생성하는가?
```

---

## 체크리스트 활용 방법

AI Agent는 작업 완료 후 다음 순서로 자기 검토를 수행한다:

1. **STEP 1~14를 순서대로** 점검한다.
2. 위반 항목 발견 시 **즉시 해당 파일을 수정**하고 체크한다.
3. 수정 후 **연관된 파일(Depends 팩토리, import 참조 등)에도 영향이 없는지** 확인한다.
4. 설계 단계 작업이었다면 **STEP 15**도 함께 점검한다.
5. 가이드 수정 작업이었다면 **STEP 16**도 함께 점검한다.
6. 모든 체크 완료 후 `harness/harness.py <projectRoot>`를 실행해 기계적으로 검사 가능한 항목이 FAIL 없이 통과하는지 최종 확인한다.

> 체크리스트는 가이드의 규칙을 요약한 것이다.
> 항목의 의도가 불명확하다면 해당 문서를 참조한다:
> - STEP 1 파일 구조 및 네이밍 → [conventions.md](conventions.md) 섹션 1-3
> - STEP 2 Domain 레이어 → [tactical-ddd.md](architecture/tactical-ddd.md), [domain-service.md](../../../docs/architecture/domain-service.md) (루트 공용), [aggregate-id.md](architecture/aggregate-id.md)
> - STEP 3 레이어 아키텍처 / CQRS / 이벤트 → [layer-architecture.md](architecture/layer-architecture.md), [cqrs-pattern.md](architecture/cqrs-pattern.md), [domain-events.md](architecture/domain-events.md), [cross-domain.md](architecture/cross-domain.md)
> - STEP 4 Repository 패턴 → [repository-pattern.md](architecture/repository-pattern.md)
> - STEP 5 DI(Depends) / 모듈 경계 → [module-pattern.md](architecture/module-pattern.md), [shared-modules.md](architecture/shared-modules.md), [bootstrap.md](architecture/bootstrap.md)
> - STEP 6 타이핑 패턴 → [conventions.md](conventions.md) 섹션 4
> - STEP 7 에러 처리 → [error-handling.md](architecture/error-handling.md)
> - STEP 8 REST API 엔드포인트 → [api-response.md](architecture/api-response.md), [rate-limiting.md](architecture/rate-limiting.md), [authentication.md](architecture/authentication.md)
> - STEP 9 Pydantic 문서화 → [conventions.md](conventions.md) 섹션 8
> - STEP 10 import → [conventions.md](conventions.md) 섹션 7
> - STEP 11 라우터/인증/스케줄링/Graceful Shutdown → [module-pattern.md](architecture/module-pattern.md), [scheduling.md](architecture/scheduling.md), [graceful-shutdown.md](architecture/graceful-shutdown.md)
> - STEP 12 DB/인프라 → [persistence.md](architecture/persistence.md), [config.md](architecture/config.md), [secret-manager.md](architecture/secret-manager.md), [observability.md](architecture/observability.md)
> - STEP 13 테스트 패턴 → [testing.md](architecture/testing.md)
> - STEP 14 전체 일관성 → 전체 문서 참조
> - STEP 15 설계 산출물 형태 → [development-process.md](../../../docs/development-process.md) (루트 공용) Agent 1~5
> - STEP 16 가이드 수정 → [CLAUDE.md](../CLAUDE.md) 가이드 관리 원칙

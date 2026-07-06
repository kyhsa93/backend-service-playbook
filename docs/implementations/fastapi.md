# FastAPI 구현체

## 개요

[FastAPI](https://fastapi.tiangolo.com/)는 Python 기반 ASGI 웹 프레임워크로, Pydantic 검증과 `Depends()` 기반 DI를 내장한다.
이 플레이북의 원칙을 FastAPI로 구체적으로 구현한 가이드와 실행 가능한 예제는 이 저장소 안의 `implementations/fastapi/`에 있다.

**→ [implementations/fastapi/CLAUDE.md](../../implementations/fastapi/CLAUDE.md)** — FastAPI 구현 상세 가이드 진입점
**→ [implementations/fastapi/docs/guide.md](../../implementations/fastapi/docs/guide.md)** — 디렉토리 구조, 네이밍, Repository/CQRS/에러처리/DI 패턴
**→ [implementations/fastapi/examples/](../../implementations/fastapi/examples/)** — Account 도메인 전체 구현 예시 (계좌 생성/입출금/정지/재개/해지 + SES 알림)
**→ [implementations/fastapi/harness/](../../implementations/fastapi/harness/)** — 가이드 준수 여부를 검증하는 자동 evaluator

이 문서는 감사(audit) 성격의 커버리지 매핑이다. `guide.md`(총 218줄)는 디렉토리 구조·네이밍·Repository·CQRS·에러처리·DI·Soft Delete만 다루는 **얇은 문서**이며, 아래 표의 상당수 항목은 guide.md 본문에 없고 `examples/`의 실제 코드에서만(또는 전혀) 확인된다. 커버리지 열은 다음 기준으로 판정했다.

- **완전**: guide.md 또는 examples/가 관용적인 FastAPI/Python 방식으로 해당 원칙을 실제로 구현
- **부분**: 언급/구현은 있으나 루트 문서 대비 얕거나 일부만 반영
- **누락**: guide.md·examples·harness 어디에도 없음 (프레임워크 무관하게 적용 가능한 주제인데도)

---

## FastAPI-specific 구현 커버리지

| 원칙 문서 (루트, 공용) | FastAPI 구현 위치 | 커버리지 | 비고 |
|---|---|:---:|---|
| [strategic-ddd.md](../architecture/strategic-ddd.md) | — | 누락 | Subdomain 분류, BC 경계, Context Map 언급이 guide.md·examples 어디에도 없음. examples는 단일 Account 도메인만 존재해 BC 분리 예시 자체가 성립하지 않음 |
| [tactical-ddd.md](../architecture/tactical-ddd.md) | `examples/src/account/domain/{account,money,transaction,events}.py` | 완전 | Aggregate Root(`Account` — 상태 가드 후 도메인 메서드 내부에서 예외 발생), Value Object(`Money` — frozen dataclass 속성 동등성), Domain Event(과거형 frozen dataclass: `AccountCreated`, `MoneyDeposited` 등)를 관용적으로 구현. guide.md 본문엔 서술이 없고 examples가 사실상의 유일한 문서 |
| [layer-architecture.md](../architecture/layer-architecture.md) | guide.md "디렉토리 구조", examples 전체 | 부분 | domain/application/interface/infrastructure 4계층 분리와 Command/Query Handler 분리는 구현됨(`Depends`로 조립). 다만 여러 Repository에 걸친 트랜잭션 전파(Node의 AsyncLocalStorage에 대응하는 Python `contextvars` 기반 TransactionManager)는 guide.md·examples 어디에도 없음 — `AsyncSession`이 요청 단위로 `Depends(get_session)`을 통해 주입될 뿐 |
| [repository-pattern.md](../architecture/repository-pattern.md) | guide.md "Repository 패턴", `examples/.../domain/repository.py` + `infrastructure/persistence/account_repository.py` | 부분 | `ABC`를 domain/에, 구현체를 infrastructure/에 두는 배치는 정확히 일치. 다만 조회 메서드가 `find_by_id`/`find_all`로 분리돼 있어, 루트 원칙("`find<Noun>s` 단일 메서드 + `take:1` 패턴, 별도 findOne 금지")과 다르다 |
| [persistence.md](../architecture/persistence.md) | `examples/.../infrastructure/persistence/account_repository.py`, `src/database.py` | 부분 | `deleted_at` 기반 soft delete와 조회 시 자동 제외(`WHERE deleted_at IS NULL`)는 정확히 구현. 마이그레이션 도구(Alembic 등)가 없고 `Base.metadata.create_all`로 스키마를 자동 생성 — 루트가 "운영 환경 금지"로 명시한 자동 동기화 방식. contextvars 기반 트랜잭션 전파 패턴도 없음 |
| [domain-service.md](../architecture/domain-service.md) | `examples/.../application/service/notification_service.py` + `infrastructure/notification/notification_service.py` | 부분 | Technical Service 패턴(인터페이스는 application/service/, 구현은 infrastructure/)은 `NotificationService`로 정확히 구현. 반면 여러 Aggregate를 조율하는 순수 Domain Service 예시는 없음(Account 하나만 존재) |
| [domain-events.md](../architecture/domain-events.md) | `examples/.../domain/events.py`, `account.py`의 `pull_events()` | 부분 | Aggregate 내부에서 이벤트를 수집하고(`_events`) Handler가 `pull_events()`로 꺼내는 패턴은 구현. 그러나 Outbox 테이블, 메시지 큐, at-least-once 멱등성, 버전 명시된 Integration Event는 전혀 없다 — Handler가 `repo.save()` 직후 `notification_service.notify(event)`를 **동기 직접 호출**하며, 알림 실패는 내부에서 삼켜 커맨드에 영향 없게 만들 뿐 Outbox의 원자성 보장과는 다른 방식 |
| [cqrs-pattern.md](../architecture/cqrs-pattern.md) | guide.md "CQRS 패턴", `examples/.../application/command|query/*_handler.py` | 완전 | `XxxHandler` 클래스 + `async def execute()` 메서드, Command/Query를 `@dataclass`로 정의하는 방식이 관용적이고 일관됨(6개 Command handler + 2개 Query handler). CommandBus/QueryBus 계층은 생략됐지만 루트 문서도 이를 선택 사항으로 명시 |
| [error-handling.md](../architecture/error-handling.md) | guide.md "에러 처리", `examples/.../domain/errors.py` + `main.py`의 `@app.exception_handler` | 부분 | Domain 예외를 plain `Exception`으로 던지고 Interface(main.py)에서 HTTP로 변환하는 레이어 분리는 원칙과 일치. 다만 응답 바디가 `{"message": str(exc)}` 뿐이며, 루트가 요구하는 표준 스키마(`statusCode`/`code`/`message`/`error`)와 에러 코드 enum(`<Domain>ErrorCode`)이 없다 |
| [api-response.md](../architecture/api-response.md) | `examples/.../application/query/get_transactions_handler.py`, `interface/rest/schemas.py` | 부분 | 목록 응답 키가 도메인 복수형(`transactions`) + `count`로 루트 규칙과 일치하고, `page`/`take` 페이지네이션(기본값 0/20)도 동일하게 구현. 다만 단건 조회를 `find_by_id`로 별도 구현해 "findOne 금지, take:1 패턴" 원칙과는 어긋남 |
| [authentication.md](../architecture/authentication.md) | — | 누락 | JWT/Bearer 토큰 발급·검증이 없다. 라우터는 `X-User-Id` 헤더 값을 검증 없이 요청자 식별자로 그대로 신뢰한다 — 인증이라기보다 신뢰 기반 헤더 전달에 가깝다 |
| [cross-cutting-concerns.md](../architecture/cross-cutting-concerns.md) | `examples/.../interface/rest/account_router.py` (Pydantic 검증) | 부분 | Pydantic `BaseModel`(`EmailStr` 등)이 요청 검증(Pipe 단계)을 프레임워크 차원에서 자동 처리. 그러나 인증 Guard(항목 자체가 없음), Correlation ID 전파, 응답 후처리(HTTP 로깅) 인터셉터는 전혀 구현되지 않음 |
| [cross-domain-communication.md](../architecture/cross-domain-communication.md) | — | 누락 | 단일 도메인(Account)만 존재 — Adapter(ACL), Integration Event 예시 자체가 성립하지 않음 |
| [directory-structure.md](../architecture/directory-structure.md) | guide.md "디렉토리 구조", `examples/src/account/*` | 부분 | 도메인 내부 4계층(domain/application/interface/infrastructure) 배치는 정확히 일치. `common/`, `database/`, `outbox/`, `task-queue/`, `config/` 같은 공용 인프라 디렉토리는 대응 기능(Outbox, TaskQueue, 관심사별 설정)이 아예 구현되지 않아 존재하지 않음 (harness의 `shared-infra` 섹션은 이 부재를 SKIP 처리) |
| [aggregate-id.md](../architecture/aggregate-id.md) | `examples/.../domain/account.py`의 `Account.create()`, `transaction.py`의 `Transaction.create()` | 부분 | ID를 Domain 레이어(팩토리 classmethod)에서 서버가 생성하는 위치는 원칙과 일치. 다만 `str(uuid.uuid4())`로 생성해 **하이픈이 포함**된다 — 루트 규칙("하이픈 제거 32자리 hex")과 형식이 다르다. guide.md는 ID 형식 규칙 자체를 언급하지 않는다 |
| [container.md](../architecture/container.md) | — | 누락 | Dockerfile이 저장소에 없다. 멀티스테이지 빌드, `.dockerignore`, 헬스체크 엔드포인트 모두 확인 불가 |
| [config.md](../architecture/config.md) | `examples/src/database.py` (`os.getenv`) | 누락 | Fail-fast 검증, 관심사별 설정 파일 분리가 없다. `DATABASE_URL` 하나를 `os.getenv` 기본값(`postgres:postgres`)으로 처리하며, 필수값 누락 시 즉시 종료하는 로직이 없다 |
| [secret-manager.md](../architecture/secret-manager.md) | — | 누락 | `SecretService` 추상화, TTL 캐시가 없다. AWS 자격증명은 `os.getenv(..., "test")` 형태의 하드코딩된 기본값으로만 처리된다 |
| [local-dev.md](../architecture/local-dev.md) | `examples/docker-compose.yml`, `examples/localstack/init-ses.sh` | 부분 | Postgres + LocalStack(SES) 조합과 healthcheck(`pg_isready`, `awslocal ses list-identities`)는 루트 패턴과 일치. `profiles: [app]`로 앱 서비스를 선택 실행하는 구성, `.env.development`/`.env.docker` 분리는 없음 |
| [file-storage.md](../architecture/file-storage.md) | — | 누락 | Presigned URL, `StorageService` 예시가 없다 |
| [graceful-shutdown.md](../architecture/graceful-shutdown.md) | `examples/main.py`의 `lifespan` | 누락 | `lifespan`은 기동 시 테이블 생성(`Base.metadata.create_all`) 용도로만 쓰인다. SIGTERM 처리, `/health/live`·`/health/ready`, readiness 우선 전환 로직이 전혀 없다 |
| [observability.md](../architecture/observability.md) | `examples/.../infrastructure/notification/notification_service.py` (`logging`) | 누락 | 표준 `logging.getLogger` + `%s` 포맷 문자열만 사용 — 루트가 요구하는 구조화 JSON 로그, snake_case 필드명, Correlation ID 전파가 없다 |
| [scheduling.md](../architecture/scheduling.md) | — | 누락 | Scheduler, TaskQueue, Cron, Task Outbox 예시가 전혀 없다 |
| [testing.md](../architecture/testing.md) | `examples/tests/test_account_e2e.py`, `test_notification_e2e.py` | 부분 | E2E 테스트(testcontainers Postgres+LocalStack, `httpx` `ASGITransport`로 실제 HTTP 요청)는 루트 원칙과 정확히 일치하며 분량도 풍부함(30개 이상 케이스). 그러나 Domain 단위 테스트(순수 `Account` 객체 테스트)와 Application 단위 테스트(Repository mock)는 하나도 없다 — 3단계 중 1단계만 구현됨 |
| [conventions.md](../conventions.md) | guide.md "파일명·모듈 네이밍", `examples/.../interface/rest/account_router.py` | 부분 | REST URL 설계(복수 명사, 비-CRUD 행위의 하위 리소스 경로 — `POST /accounts/{id}/suspend`, `/deposit`, `/withdraw` 등)는 예시가 루트 규칙을 정확히 따른다. Rate Limiting 원칙은 guide.md·examples 어디에도 없다 |

### 커버리지 요약

- **완전**: 2건 — tactical-ddd, cqrs-pattern
- **부분**: 13건 — layer-architecture, repository-pattern, persistence, domain-service, domain-events, error-handling, api-response, cross-cutting-concerns, directory-structure, aggregate-id, local-dev, testing, conventions
- **누락**: 10건 — strategic-ddd, authentication, cross-domain-communication, container, config, secret-manager, file-storage, graceful-shutdown, observability, scheduling
- **N/A**: 0건 (24개 원칙 문서 모두 FastAPI 백엔드에 일반적으로 적용 가능하다고 판단)

### harness 검증 범위 참고

`implementations/fastapi/harness/harness.py`는 현재 9개 섹션(file-naming, repository-abc, repository-impl, handler-placement, domain-purity, directory-structure, shared-infra, event-placement, AST 기반 layer-dependency)을 검사하지만, 전부 **파일 배치·네이밍·import 구조**에 대한 정적 검사이며 위 표의 "부분"/"완전" 판정에 쓰인 비즈니스 규칙(ID 형식, 에러 응답 스키마, soft delete 사용 여부, 트랜잭션 전파, 페이지네이션 규약 등)은 하나도 검증하지 않는다. 즉 guide.md가 "완전"하다고 표시된 항목도 harness가 회귀를 잡아주지는 않는다.

---

## FastAPI 전용, 대응 root 문서 없음

- Pydantic `BaseModel`/`EmailStr` 기반 요청·응답 스키마 자동 검증 및 OpenAPI(Swagger) 문서 자동 생성
- `Depends()` 함수형 DI 그래프 — 클래스 데코레이터 없이 provider 함수 조합으로 의존성을 구성 (`account_router.py`의 `_repo`, `_notification_service`)
- `app.dependency_overrides`를 이용한 테스트 환경 의존성 치환 (E2E 테스트에서 `get_session`을 testcontainers 세션으로 교체)

---

## FastAPI 선택 이유

- Python 표준 라이브러리의 `ABC`/`abstractmethod`만으로 Repository·Technical Service 인터페이스를 정의할 수 있어, 프레임워크 데코레이터 없이 Domain/Application 순수성을 유지하기 쉽다 (harness의 domain-purity 검사도 이를 전제로 한다).
- Pydantic이 DTO 검증과 직렬화를 하나의 `BaseModel`로 통합해, Interface 계층을 얇게 유지하는 원칙(Interface DTO = thin wrapper)을 자연스럽게 지원한다.
- `async`/`await` 네이티브 지원으로 SQLAlchemy `AsyncSession`, `aioboto3` 등 I/O-바운드 작업을 일관되게 비동기 처리한다.
- `Depends()` 기반 함수형 DI로 Handler 조립이 라우터 코드 안에 명시적으로 드러나, 컨테이너 매직 없이 의존성 흐름을 코드만 보고 추적할 수 있다.

# FastAPI 구현체

## 개요

[FastAPI](https://fastapi.tiangolo.com/)는 Python 기반 ASGI 웹 프레임워크로, Pydantic 검증과 `Depends()` 기반 DI를 내장한다.
이 플레이북의 원칙을 FastAPI로 구체적으로 구현한 가이드와 실행 가능한 예제는 이 저장소 안의 `implementations/fastapi/`에 있다.

**→ [implementations/fastapi/CLAUDE.md](../../implementations/fastapi/CLAUDE.md)** — FastAPI 구현 상세 가이드 진입점 (키워드 → `docs/architecture/*.md` 인덱스)
**→ [implementations/fastapi/docs/architecture/](../../implementations/fastapi/docs/architecture/)** — root의 21개 주제에 대응하는 FastAPI 전용 elaboration 문서 (NestJS `docs/architecture/`와 동일한 구조)
**→ [implementations/fastapi/examples/](../../implementations/fastapi/examples/)** — Account 도메인 전체 구현 예시 (계좌 생성/입출금/정지/재개/해지 + SES 알림)
**→ [implementations/fastapi/harness/](../../implementations/fastapi/harness/)** — 가이드 준수 여부를 검증하는 자동 evaluator

이 문서는 감사(audit) 성격의 커버리지 매핑이다. 이전 판(2026-07 이전)은 `docs/guide.md`(총 218줄, 디렉토리 구조·네이밍·Repository·CQRS·에러처리·DI·Soft Delete만 다루는 얇은 단일 문서) 기준으로 작성되어 24개 원칙 중 2건만 "완전"이었다. 이후 `docs/guide.md`는 삭제되고, root의 21개 주제 각각에 대응하는 `docs/architecture/*.md`가 신설되어(3개 주제 — strategic-ddd, cross-domain-communication, domain-service — 는 단일 도메인/단일 BC라는 이 저장소의 특성상 FastAPI 전용 페이지를 만들지 않음, NestJS도 동일 판단) 문서 커버리지가 크게 개선되었다.

**중요한 구분**: 아래 표의 커버리지 열은 이제 "**문서가 root 원칙을 정확하고 충분히 설명하는가**"를 판정한다. 문서가 "완전"이어도 `examples/`의 실제 코드가 그 원칙을 100% 따른다는 뜻은 아니다 — 각 문서는 코드의 현재 격차를 "알려진 격차" 섹션으로 명시하고 있으며, 아래 표의 **비고** 열에도 코드 격차가 있으면 반드시 함께 적는다.

- **완전**: `docs/architecture/<topic>.md`가 신설되어 root 원칙을 FastAPI/Python 관용구로 정확히 설명한다 (코드가 아직 그 원칙을 완전히 따르지 않더라도, 문서가 격차를 명시하고 올바른 패턴을 제시하면 완전으로 판정)
- **부분**: 문서가 있으나 다른 문서에 흡수되어 있거나(전용 페이지 없음) 설명이 얕음
- **누락**: FastAPI 전용 문서가 없고, 이 저장소 구조(단일 도메인)상 예시를 만들기도 어려움

---

## FastAPI-specific 구현 커버리지

| 원칙 문서 (루트, 공용) | FastAPI 구현 위치 | 커버리지 | 비고 |
|---|---|:---:|---|
| [strategic-ddd.md](../architecture/strategic-ddd.md) | — | 누락 | Subdomain 분류, BC 경계, Context Map — FastAPI 전용 문서 없음 (NestJS도 동일). examples는 단일 Account 도메인만 존재해 BC 분리 예시 자체가 성립하지 않음 |
| [tactical-ddd.md](../architecture/tactical-ddd.md) | `docs/architecture/tactical-ddd.md`, `examples/src/account/domain/{account,money,transaction,events}.py` | 완전 | Aggregate Root(`Account` — 일반 클래스, 도메인 메서드 내부에서 불변식 검증), Entity(`Transaction` — frozen dataclass + 식별자), Value Object(`Money` — frozen dataclass 속성 동등성), Domain Event(과거형 frozen dataclass)를 각각 왜 그렇게 모델링했는지까지 문서화. 코드와 완전히 일치, 격차 없음 |
| [layer-architecture.md](../architecture/layer-architecture.md) | `docs/architecture/layer-architecture.md`, examples 전체 | 완전 | 4계층 분리, Command/Query Handler, `Depends` 팩토리를 통한 DI 바인딩, Technical Service 패턴(`notification_service.py`)까지 문서화. 코드 격차: 여러 Repository에 걸친 명시적 트랜잭션 전파(`contextvars` 기반)는 없음 — 대신 `Depends(get_session)`의 요청 스코프 캐싱으로 같은 효과를 내는 것으로 문서에서 설명(persistence.md 참조) |
| [repository-pattern.md](../architecture/repository-pattern.md) | `docs/architecture/repository-pattern.md`, `examples/.../domain/repository.py` + `infrastructure/persistence/account_repository.py` | 완전 | ABC/구현체 배치, `save()` 하나로 upsert, 동적 필터 패턴은 코드와 문서가 일치. **코드 격차 (문서에 명시)**: 조회 메서드가 `find_by_id`/`find_all`로 분리돼 있어 루트의 "`find<Noun>s` 단일 메서드 + `take:1`" 컨벤션과 다르다 — 문서가 두 형태의 트레이드오프와 마이그레이션 방법을 모두 제시 |
| [persistence.md](../architecture/persistence.md) | `docs/architecture/persistence.md`, `examples/.../infrastructure/persistence/account_repository.py`, `src/database.py` | 완전 | 트랜잭션 경계(`Depends(get_session)` 요청 스코프), soft delete(`deleted_at IS NULL`)는 코드와 문서가 일치. **코드 격차 (문서에 명시)**: Alembic 등 마이그레이션 도구가 없고 `Base.metadata.create_all`로 스키마를 자동 생성 — 문서가 Alembic 도입 절차를 구체적으로 제시 |
| [domain-service.md](../architecture/domain-service.md) | `docs/architecture/layer-architecture.md`(Technical Service 섹션에 흡수), `examples/.../application/service/notification_service.py` + `infrastructure/notification/notification_service.py` | 부분 | FastAPI 전용 `domain-service.md` 페이지는 만들지 않음(NestJS도 `cross-domain.md`/`design-principles.md`로 대체하는 등 언어별 재량) — 대신 Technical Service 패턴(`NotificationService` ABC + `SesNotificationService` 구현)을 `layer-architecture.md`에서 상세히 다룸. 여러 Aggregate를 조율하는 순수 Domain Service 예시는 여전히 없음(Account 하나만 존재) |
| [domain-events.md](../architecture/domain-events.md) | `docs/architecture/domain-events.md`, `examples/src/outbox/`, `examples/.../application/event/*_event_handler.py` | 완전 | 이벤트 수집(`_events`/`pull_events()`), Outbox 적재(`SqlAlchemyAccountRepository.save()`가 같은 세션에서 `OutboxWriter.save_all()` 호출), 드레인(`OutboxRelay.process_pending()`을 6개 Command Handler 모두가 `repo.save()` 직후 동기 호출)까지 코드와 문서가 일치 — dual-write 격차는 해소됨. 남은 항목은 이벤트 핸들러 멱등성(Ledger 기반 중복 발송 방지)뿐이며, 문서가 이를 향후 개선 지점으로 명시 |
| [cqrs-pattern.md](../architecture/cqrs-pattern.md) | `docs/architecture/cqrs-pattern.md`, `examples/.../application/command|query/*_handler.py` | 완전 | `XxxHandler` + `async def execute()`, `@dataclass` Command/Query는 코드와 문서가 일치(6 Command + 2 Query handler). Bus 도입 기준과 트레이드오프도 문서화. CommandBus/QueryBus 생략은 루트도 선택사항으로 명시하므로 격차 아님 |
| [error-handling.md](../architecture/error-handling.md) | `docs/architecture/error-handling.md`, `examples/.../domain/{errors,error_codes}.py`, `examples/src/common/error_response.py` + `main.py`의 `@app.exception_handler` | 완전 | Domain 예외 → Interface 변환의 레이어 분리, `<Domain>ErrorCode` enum(예외와 1:1), `build_error_response()`가 조립하는 `statusCode`/`code`/`message`/`error` 4필드 응답, `VALIDATION_FAILED` 고정 422 처리까지 코드와 문서가 완전히 일치 — 코드 격차 해소됨 |
| [api-response.md](../architecture/api-response.md) | `docs/architecture/api-response.md`, `examples/.../application/query/get_transactions_handler.py`, `interface/rest/schemas.py` | 완전 | 목록 응답 키(도메인 복수형 + `count`), `page`/`take` 페이지네이션, Result 객체 분리는 코드와 문서가 일치. 단건 조회의 `find_by_id` 분리 격차는 `repository-pattern.md`에서 상세히 다루고 여기서는 API 응답 계층에 미치는 영향만 요약 |
| [authentication.md](../architecture/authentication.md) | `docs/architecture/authentication.md`, `examples/src/auth/interface/rest/dependencies.py`, `examples/.../account/interface/rest/account_router.py` | 완전 | `HTTPBearer` + `Depends(get_current_user)`가 `JwtAuthService.verify_token()`으로 실제 JWT 검증을 수행하며, 라우터 전체에 `dependencies=[Depends(get_current_user)]`로 일괄 적용되어 있다 — 코드와 문서 완전 일치, X-User-Id 신뢰 패턴은 해소됨 |
| [cross-cutting-concerns.md](../architecture/cross-cutting-concerns.md) | `docs/architecture/cross-cutting-concerns.md`, `examples/.../interface/rest/account_router.py` (Pydantic 검증) | 완전 | Pydantic `BaseModel` 자동 검증(Pipe 단계 대응)은 코드와 문서가 일치. **코드 격차 (문서에 명시)**: `@app.middleware` 자체가 없어 Correlation ID 주입, 요청 로깅 후처리가 전혀 없다 — 문서가 `contextvars` 기반 미들웨어 구현을 구체적으로 제시 |
| [cross-domain-communication.md](../architecture/cross-domain-communication.md) | — | 누락 | 단일 도메인(Account)만 존재 — Adapter(ACL), Integration Event 예시 자체가 성립하지 않음. FastAPI 전용 문서 없음 |
| [directory-structure.md](../architecture/directory-structure.md) | `docs/architecture/directory-structure.md`, `examples/src/account/*` | 완전 | 4계층 배치, Technical Service 분리(`application/service/` + `infrastructure/notification/`)까지 실제 트리 기준으로 문서화. `common/`, `config/` 등 공용 디렉토리는 아직 필요하지 않음(도메인이 하나뿐)을 문서가 명시하고, 향후 추가 시 위치를 미리 규정 |
| [aggregate-id.md](../architecture/aggregate-id.md) | `docs/architecture/aggregate-id.md`, `examples/.../domain/account.py`의 `Account.create()`, `transaction.py`의 `Transaction.create()` | 완전 | ID를 Domain 레이어(팩토리 classmethod)에서 생성하는 위치는 코드와 문서가 일치. **코드 격차 (문서에 명시)**: `str(uuid.uuid4())`로 생성해 하이픈이 포함된다 — 루트 규칙("하이픈 제거 32자리 hex")과 다르다. 문서가 `uuid.uuid4().hex` 기반 `generate_id()` 유틸과 `CHAR(32)` 컬럼 타입까지 구체적으로 제시 |
| [container.md](../architecture/container.md) | `docs/architecture/container.md` | 완전 | **코드 격차 (문서에 명시)**: Dockerfile이 저장소에 없다 — 문서가 `asyncpg`/`aioboto3` 빌드 의존성을 고려한 멀티스테이지 Dockerfile, `.dockerignore`, non-root 사용자, 헬스체크 엔드포인트까지 구체적으로 제시. 실제 Dockerfile 파일 자체는 아직 추가되지 않음(examples/ 미변경 원칙) |
| [config.md](../architecture/config.md) | `docs/architecture/config.md`, `examples/src/config/jwt_config.py`, `examples/src/config/validator.py` | 완전 | `JwtConfig`가 관심사별 설정 클래스로 분리되어 있고, `validator.py:20`의 `validate_env()`가 `JWT_SECRET` 등 필수값 누락 시 `sys.exit(1)`로 fail-fast — 코드와 문서 완전 일치 |
| [secret-manager.md](../architecture/secret-manager.md) | `docs/architecture/secret-manager.md`, `examples/src/config/aws_config.py`, `examples/.../aws_secret_service.py`, `examples/.../notification_service.py` | 완전 | `AwsConfig.client_kwargs()`로 AWS 자격증명이 캡슐화되어 있고 `aws_secret_service.py`/`notification_service.py`가 이를 통해 클라이언트를 생성 — 산발적 `os.getenv` 하드코딩 격차는 해소됨 |
| [local-dev.md](../architecture/local-dev.md) | `docs/architecture/local-dev.md`, `examples/docker-compose.yml`, `examples/localstack/init-ses.sh` | 완전 | Postgres + LocalStack(SES) 조합, healthcheck, 버전 고정 LocalStack 이미지는 이미 루트 원칙과 정확히 일치 — 문서가 이를 그대로 확인. 남은 격차(`profiles: [app]` 앱 서비스, `.env.*` 분리)도 명시하고 확장 방법을 제시 |
| [file-storage.md](../architecture/file-storage.md) | `docs/architecture/file-storage.md` | 완전 | Account 도메인에 파일 업로드 기능 자체가 없어 코드 대응은 없음(순수 신규 문서) — `notification_service.py`가 이미 쓰는 `aioboto3` 클라이언트 패턴을 재사용하는 `StorageService` Technical Service로 문서화 |
| [graceful-shutdown.md](../architecture/graceful-shutdown.md) | `docs/architecture/graceful-shutdown.md`, `examples/main.py`의 `lifespan` | 완전 | **코드 격차 (문서에 명시)**: `lifespan`은 현재 기동(테이블 생성)만 처리하고 종료 블록이 비어 있다 — SIGTERM 시 리소스 정리, `/health/live`·`/health/ready` 엔드포인트가 없다. 문서가 `lifespan`을 기동/종료 모두 채우는 올바른 구현을 구체적으로 제시 (FastAPI의 `lifespan` 자체는 이미 올바른 확장 지점이라는 점도 명시) |
| [observability.md](../architecture/observability.md) | `docs/architecture/observability.md`, `examples/.../infrastructure/notification/notification_service.py` (`logging`) | 완전 | **코드 격차 (문서에 명시)**: 표준 `logging` + `%s` 포맷 문자열만 사용 — 구조화 JSON, Correlation ID가 없다. 문서가 stdlib `logging` + 커스텀 `JsonFormatter`(structlog 대신 선택한 이유 포함) + `contextvars` 기반 Correlation ID를 구체적으로 제시 |
| [scheduling.md](../architecture/scheduling.md) | `docs/architecture/scheduling.md` | 완전 | 이 저장소에 스케줄링 유스케이스 자체가 없어 코드 대응은 없음(순수 신규 문서) — `BackgroundTasks`/APScheduler/Celery의 트레이드오프를 비교하고 Outbox Relay를 실행하는 예시로 [domain-events.md](../../implementations/fastapi/docs/architecture/domain-events.md)와 연결 |
| [testing.md](../architecture/testing.md) | `docs/architecture/testing.md`, `examples/tests/test_account_e2e.py`, `test_notification_e2e.py` | 완전 | E2E 테스트(testcontainers, `httpx.ASGITransport`)는 이미 루트 원칙과 정확히 일치(30개 이상 케이스) — 문서가 이를 확인. **코드 격차 (문서에 명시)**: Domain 단위 테스트, Application 단위 테스트(mock 기반)가 하나도 없다 — 문서가 `pytest` + `unittest.mock.AsyncMock` 기반 예시 테스트 코드를 구체적으로 제시 |
| [conventions.md](../conventions.md) | `docs/architecture/directory-structure.md`(파일명·네이밍 섹션 흡수), `docs/architecture/rate-limiting.md`(Rate Limiting 섹션), `examples/.../interface/rest/account_router.py`, `examples/src/common/rate_limit.py` | 완전 | REST URL 설계(복수 명사, `POST /accounts/{id}/suspend` 등 하위 리소스 경로)는 코드가 루트 규칙을 정확히 따르고, 파일명·클래스명 네이밍 규칙은 `directory-structure.md`에 통합됨. Rate Limiting 원칙도 `slowapi` 기반으로 실제 구현되어(전역 `default_limits` + 쓰기 엔드포인트 `@limiter.limit()`) 더 이상 코드 격차가 아니다 |

### 커버리지 요약

- **완전**: 22건 — tactical-ddd, layer-architecture, repository-pattern, persistence, domain-events, cqrs-pattern, error-handling, api-response, authentication, cross-cutting-concerns, directory-structure, aggregate-id, container, config, secret-manager, local-dev, file-storage, graceful-shutdown, observability, scheduling, testing, conventions
- **부분**: 1건 — domain-service (layer-architecture.md에 흡수)
- **누락**: 2건 — strategic-ddd, cross-domain-communication (단일 도메인/단일 BC 구조상 FastAPI 전용 예시 성립 불가, NestJS와 동일한 판단)
- **N/A**: 0건

이전 판(guide.md 기준) 대비 "완전" 판정이 2건 → 22건으로 늘었지만, 이는 **문서 품질과 정확성이 개선된 것**이지 `examples/`의 실제 코드가 21개 원칙을 모두 준수하게 됐다는 뜻이 아니다. 위 표의 "코드 격차 (문서에 명시)" 문구가 붙은 항목(repository-pattern, persistence, api-response, cross-cutting-concerns, aggregate-id, container, graceful-shutdown, observability, testing)은 코드 변경이 필요한 실질적 후속 작업이다. 각 문서의 "알려진 격차" 섹션이 그 상세 내용이다. **domain-events는 Outbox 패턴이, authentication은 JWT 검증이, config/secret-manager는 fail-fast 검증과 자격증명 캡슐화가, error-handling은 에러 코드 enum과 표준 4필드 응답이 실제로 구현되어 이 목록에서 제외되었다** — dual-write 알림 유실, X-User-Id 무검증 신뢰, 자격증명 산발적 하드코딩, `{"message": ...}` 단일 필드 에러 응답이라는 이전의 주요 코드 격차가 해소된 항목들이다.

### harness 검증 범위 참고

`implementations/fastapi/harness/harness.py`는 현재 9개 섹션(file-naming, repository-abc, repository-impl, handler-placement, domain-purity, directory-structure, shared-infra, event-placement, AST 기반 layer-dependency)을 검사하지만, 전부 **파일 배치·네이밍·import 구조**에 대한 정적 검사이며 위 표의 코드 격차(ID 형식, 에러 응답 스키마, Outbox 유무, 트랜잭션 전파, fail-fast 검증, 구조화 로깅, 단위 테스트 존재 여부 등)는 하나도 검증하지 않는다. 문서가 "완전"해진 지금도 harness는 이 격차들의 회귀를 잡아주지 않는다 — 코드 개선 작업 시 harness 룰 추가도 함께 검토할 만하다.

---

## FastAPI 전용, 대응 root 문서 없음

- Pydantic `BaseModel`/`EmailStr` 기반 요청·응답 스키마 자동 검증 및 OpenAPI(Swagger) 문서 자동 생성
- `Depends()` 함수형 DI 그래프 — 클래스 데코레이터 없이 provider 함수 조합으로 의존성을 구성 (`account_router.py`의 `_repo`, `_notification_service`)
- `app.dependency_overrides`를 이용한 테스트 환경 의존성 치환 (E2E 테스트에서 `get_session`을 testcontainers 세션으로 교체)
- `implementations/fastapi/docs/architecture/bootstrap.md` — `main.py`의 `FastAPI(...)` 생성, `lifespan`, 라우터/예외 핸들러 등록, FastAPI 자동 OpenAPI(`/docs`)가 NestJS의 명시적 `SwaggerModule.setup()`을 대체하는 방식
- `implementations/fastapi/docs/architecture/cross-domain.md` — Adapter 패턴(ACL)의 FastAPI 구현 예시(가상 시나리오): `application/adapter/`의 ABC + `infrastructure/`의 구현체 + `Depends` 바인딩 (원칙은 [cross-domain-communication.md](../architecture/cross-domain-communication.md) 참고)
- `implementations/fastapi/docs/architecture/design-principles.md` — 이 저장소 다른 21개 문서를 관통하는 핵심 설계 원칙 13개 요약
- `implementations/fastapi/docs/architecture/module-pattern.md` — DI 컨테이너/모듈 데코레이터가 없는 FastAPI에서 Python 패키지 트리와 `Depends` 팩토리 함수가 NestJS의 `@Module`/`providers`를 대체하는 방식, 순환 import 해소
- `implementations/fastapi/docs/architecture/rate-limiting.md` — `slowapi` 기반 Rate Limiting 구현 가이드 (`examples/`에 실제 구현됨: 전역 `default_limits` + 쓰기 엔드포인트별 `@limiter.limit()`)
- `implementations/fastapi/docs/architecture/shared-modules.md` — 도메인에 속하지 않는 공유 코드(`src/database.py`, `src/outbox/`는 이미 존재, `src/common/`·`src/config/`·`src/auth/`는 두 번째 도메인 추가 시 권장 구조)의 위치 규칙

---

## FastAPI 선택 이유

- Python 표준 라이브러리의 `ABC`/`abstractmethod`만으로 Repository·Technical Service 인터페이스를 정의할 수 있어, 프레임워크 데코레이터 없이 Domain/Application 순수성을 유지하기 쉽다 (harness의 domain-purity 검사도 이를 전제로 한다).
- Pydantic이 DTO 검증과 직렬화를 하나의 `BaseModel`로 통합해, Interface 계층을 얇게 유지하는 원칙(Interface DTO = thin wrapper)을 자연스럽게 지원한다.
- `async`/`await` 네이티브 지원으로 SQLAlchemy `AsyncSession`, `aioboto3` 등 I/O-바운드 작업을 일관되게 비동기 처리한다.
- `Depends()` 기반 함수형 DI로 Handler 조립이 라우터 코드 안에 명시적으로 드러나, 컨테이너 매직 없이 의존성 흐름을 코드만 보고 추적할 수 있다.

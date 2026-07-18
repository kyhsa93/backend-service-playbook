# FastAPI 구현체

## 개요

[FastAPI](https://fastapi.tiangolo.com/)는 Python 기반 ASGI 웹 프레임워크로, Pydantic 검증과 `Depends()` 기반 DI를 내장한다.
이 플레이북의 원칙을 FastAPI로 구체적으로 구현한 가이드와 실행 가능한 예제는 이 저장소 안의 `implementations/fastapi/`에 있다.

**→ [implementations/fastapi/CLAUDE.md](../../implementations/fastapi/CLAUDE.md)** — FastAPI 구현 상세 가이드 진입점 (키워드 → `docs/architecture/*.md` 인덱스)
**→ [implementations/fastapi/docs/architecture/](../../implementations/fastapi/docs/architecture/)** — root의 21개 주제에 대응하는 FastAPI 전용 elaboration 문서 (NestJS `docs/architecture/`와 동일한 구조)
**→ [implementations/fastapi/examples/](../../implementations/fastapi/examples/)** — Account/Card/Payment 도메인 전체 구현 예시 (계좌 생성/입출금/정지/재개/해지 + SES 알림, 카드 발급 + Account↔Card 연동, 카드결제/결제취소/환불 + Payment↔Account 양방향 Integration Event + Domain Service)
**→ [implementations/fastapi/harness/](../../implementations/fastapi/harness/)** — 가이드 준수 여부를 검증하는 자동 evaluator

이 문서는 root 원칙 문서와 FastAPI 구현 문서 간의 커버리지 매핑이다. root의 21개 주제 각각에 대응하는 `docs/architecture/*.md`가 있다 — 그중 2개 주제(strategic-ddd, domain-service)는 언어/구조 무관이라 FastAPI 전용 페이지를 만들지 않는다(NestJS도 동일 판단). cross-domain-communication은 [`cross-domain.md`](../../implementations/fastapi/docs/architecture/cross-domain.md)가 대응한다.

**중요한 구분**: 아래 표의 커버리지 열은 "**문서가 root 원칙을 정확하고 충분히 설명하는가**"를 판정한다. 문서가 "완전"이어도 `examples/`의 실제 코드가 그 원칙을 100% 따른다는 뜻은 아니다 — 각 문서는 코드의 현재 격차를 "알려진 격차" 섹션으로 명시하고 있으며, 아래 표의 **비고** 열에도 코드 격차가 있으면 반드시 함께 적는다.

- **완전**: `docs/architecture/<topic>.md`가 신설되어 root 원칙을 FastAPI/Python 관용구로 정확히 설명한다 (코드가 아직 그 원칙을 완전히 따르지 않더라도, 문서가 격차를 명시하고 올바른 패턴을 제시하면 완전으로 판정)
- **부분**: 문서가 있으나 다른 문서에 흡수되어 있거나(전용 페이지 없음) 설명이 얕음
- **누락**: FastAPI 전용 문서가 없고, 언어/구조 무관이라 root 문서를 그대로 참조

---

## FastAPI-specific 구현 커버리지

| 원칙 문서 (루트, 공용) | FastAPI 구현 위치 | 커버리지 | 비고 |
|---|---|:---:|---|
| [strategic-ddd.md](../architecture/strategic-ddd.md) | — | 누락 | Subdomain 분류, BC 경계, Context Map — FastAPI 전용 문서 없음(NestJS도 동일, 언어 무관 주제) |
| [tactical-ddd.md](../architecture/tactical-ddd.md) | `docs/architecture/tactical-ddd.md`, `examples/src/account/domain/{account,money,transaction,events}.py` | 완전 | Aggregate Root(`Account` — 일반 클래스, 도메인 메서드 내부에서 불변식 검증), Entity(`Transaction` — frozen dataclass + 식별자), Value Object(`Money` — frozen dataclass 속성 동등성), Domain Event(과거형 frozen dataclass)를 각각 왜 그렇게 모델링했는지까지 문서화. 코드와 일치, 격차 없음 |
| [layer-architecture.md](../architecture/layer-architecture.md) | `docs/architecture/layer-architecture.md`, examples 전체 | 완전 | 4계층 분리, Command/Query Handler, `Depends` 팩토리를 통한 DI 바인딩, Technical Service 패턴(`notification_service.py`)까지 문서화. 코드 격차: 여러 Repository에 걸친 명시적 트랜잭션 전파(`contextvars` 기반)는 없음 — 대신 `Depends(get_session)`의 요청 스코프 캐싱으로 같은 효과를 내는 것으로 문서에서 설명(persistence.md 참조) |
| [repository-pattern.md](../architecture/repository-pattern.md) | `docs/architecture/repository-pattern.md`, `examples/.../domain/repository.py` + `infrastructure/persistence/account_repository.py` | 완전 | ABC/구현체 배치, `save()` 하나로 upsert, 동적 필터 패턴, 조회 메서드 `find_accounts()` 단일화(단건 조회는 `take:1` + 첫 항목 추출)까지 코드와 문서가 일치한다 — java/kotlin-springboot의 `findAccounts`와 형태가 같다 |
| [persistence.md](../architecture/persistence.md) | `docs/architecture/persistence.md`, `examples/.../infrastructure/persistence/account_repository.py`, `src/database.py`, `alembic.ini` | 완전 | 트랜잭션 경계(`Depends(get_session)` 요청 스코프), soft delete(`deleted_at IS NULL`)는 코드와 문서가 일치. Alembic 마이그레이션이 이미 도입되어 있다 — `create_all`은 testcontainers 픽스처 등 로컬/테스트 전용으로 한정. |
| [domain-service.md](../architecture/domain-service.md) | `docs/architecture/layer-architecture.md`(Technical Service 섹션 + 신설 "Domain Service" 섹션), `examples/src/payment/domain/refund_eligibility_service.py` + `payment.py`/`refund.py`, `examples/.../application/command/request_refund_handler.py` | 완전 | FastAPI 전용 `domain-service.md` 페이지는 만들지 않지만(NestJS도 동일 재량), `layer-architecture.md`에 Technical Service(`NotificationService` ABC + `SesNotificationService`)와 Domain Service(`RefundEligibilityService`) 두 패턴을 모두 실제 코드로 다룬다. `RefundEligibilityService.evaluate(payment, refund)`가 `Payment`/`Refund` 두 Aggregate를 함께 받아 "원 결제가 COMPLETED여야 하고 환불 금액이 결제 금액을 넘을 수 없다"를 판단하는, 한 BC 안에서 여러 Aggregate를 조율하는 실제 동작하는 예시다(`RequestRefundHandler`가 두 Repository를 로드해 위임) — nestjs 구현체와 동일한 패턴(root domain-service.md가 인용하는 코드)을 FastAPI/Python 관용구로 재현했다 |
| [domain-events.md](../architecture/domain-events.md) | `docs/architecture/domain-events.md`, `examples/src/outbox/`, `examples/.../application/event/*_event_handler.py`, `examples/.../application/integration_event/` | 완전 | 이벤트 수집(`_events`/`pull_events()`), Outbox 적재(Repository `save()`가 같은 세션에서 `OutboxWriter.save_all()` 호출), 드레인(`OutboxRelay.process_pending()`을 Command Handler가 `repo.save()` 직후 동기 호출, 여러 패스로 반복 드레인)까지 코드와 문서가 일치한다. Account→Card Integration Event(`account.suspended.v1`/`account.closed.v1`)도 실제로 구현되어 있다. 이벤트 핸들러 멱등성도 Level 2(Ledger) 적용됨 — `OutboxRelay`가 넘기는 `outbox_event_id`를 `SentEmailModel.outbox_event_id`에 기록해 재전달 시 중복 발송을 건너뛴다 |
| [cqrs-pattern.md](../architecture/cqrs-pattern.md) | `docs/architecture/cqrs-pattern.md`, `examples/.../application/command|query/*_handler.py` | 완전 | `XxxHandler` + `async def execute()`, `@dataclass` Command/Query는 코드와 문서가 일치. Bus 도입 기준과 트레이드오프도 문서화. CommandBus/QueryBus 생략은 루트도 선택사항으로 명시하므로 격차 아님 |
| [error-handling.md](../architecture/error-handling.md) | `docs/architecture/error-handling.md`, `examples/.../domain/{errors,error_codes}.py`, `examples/src/common/error_response.py` + `main.py`의 `@app.exception_handler` | 완전 | Domain 예외 → Interface 변환의 레이어 분리, `<Domain>ErrorCode` enum(예외와 1:1), `build_error_response()`가 조립하는 `statusCode`/`code`/`message`/`error` 4필드 응답, `VALIDATION_FAILED` 고정 422 처리까지 코드와 문서가 일치한다 |
| [api-response.md](../architecture/api-response.md) | `docs/architecture/api-response.md`, `examples/.../application/query/get_transactions_handler.py`, `interface/rest/schemas.py` | 완전 | 목록 응답 키(도메인 복수형 + `count`), `page`/`take` 페이지네이션, Result 객체 분리는 코드와 문서가 일치. 단건 조회도 목록 조회와 같은 `AccountRepository.find_accounts()`에 의존한다(상세는 `repository-pattern.md`) |
| [authentication.md](../architecture/authentication.md) | `docs/architecture/authentication.md`, `examples/src/auth/interface/rest/dependencies.py`, `examples/.../account/interface/rest/account_router.py` | 완전 | `HTTPBearer` + `Depends(get_current_user)`가 `JwtAuthService.verify_token()`으로 실제 JWT 검증을 수행하며, 라우터 전체에 `dependencies=[Depends(get_current_user)]`로 일괄 적용되어 있다. `POST /auth/sign-in`도 `Credential` Aggregate(bcrypt 해시)와 `PasswordHasher`로 실제 비밀번호 검증을 거친 뒤에만 토큰을 발급한다 — 코드와 문서가 일치한다 |
| [cross-cutting-concerns.md](../architecture/cross-cutting-concerns.md) | `docs/architecture/cross-cutting-concerns.md`, `main.py`의 `correlation_id_middleware`, `examples/.../interface/rest/account_router.py` (Pydantic 검증) | 완전 | `@app.middleware("http")`로 등록된 `correlation_id_middleware`(Correlation ID 주입 + 요청 로깅), `Depends(get_current_user)` 기반 인증, Pydantic `BaseModel` 자동 검증(Pipe 단계 대응), `@app.exception_handler` 에러 변환까지 모두 실제 코드로 구현되어 있어 코드와 문서가 일치한다 |
| [cross-domain-communication.md](../architecture/cross-domain-communication.md) | FastAPI 전용 대응 문서는 [`cross-domain.md`](../../implementations/fastapi/docs/architecture/cross-domain.md)(아래 "FastAPI 전용" 절) | 완전 | Account/Card 두 BC가 실제로 존재해 동기 Adapter(`card/application/adapter/`의 ABC + `infrastructure/`의 구현체)와 비동기 Integration Event(`account.suspended.v1`/`account.closed.v1`) 양쪽 모두 실제 코드로 다룬다 |
| [directory-structure.md](../architecture/directory-structure.md) | `docs/architecture/directory-structure.md`, `examples/src/{account,card,auth,common,config,outbox}/` | 완전 | 4계층 배치, Technical Service 분리(`application/service/` + `infrastructure/notification/`)까지 실제 트리 기준으로 문서화. `common/`, `config/`, `auth/`, `outbox/`가 모두 Account/Card 두 도메인이 공유하는 실제 최상위 패키지로 존재한다 |
| [aggregate-id.md](../architecture/aggregate-id.md) | `docs/architecture/aggregate-id.md`, `examples/src/common/generate_id.py`, `examples/.../domain/account.py`의 `Account.create()` | 완전 | `generate_id()`(`uuid.uuid4().hex` — 하이픈 제거 32자리 hex)를 Domain 레이어(팩토리 classmethod)에서 호출하는 위치와 형식 모두 코드와 문서가 일치, 격차 없음 |
| [container.md](../architecture/container.md) | `docs/architecture/container.md`, `examples/Dockerfile` | 완전 | 멀티스테이지 Dockerfile(`asyncpg`/`aioboto3` 빌드 의존성 고려), `.dockerignore`, non-root 사용자, `HEALTHCHECK` 지시문이 모두 실제 코드로 존재한다 — 코드와 문서가 일치 |
| [config.md](../architecture/config.md) | `docs/architecture/config.md`, `examples/src/config/{jwt_config,aws_config,database_config,rate_limit_config,validator}.py` | 완전 | 관심사별 설정 클래스(JWT/AWS/DB/Rate Limit)로 분리되어 있고, `validator.py`의 `validate_env()`가 필수값 누락 시 `sys.exit(1)`로 fail-fast — 코드와 문서 일치 |
| [secret-manager.md](../architecture/secret-manager.md) | `docs/architecture/secret-manager.md`, `examples/src/config/aws_config.py`, `examples/.../aws_secret_service.py`, `examples/.../notification_service.py` | 완전 | `AwsConfig.client_kwargs()`로 AWS 자격증명이 캡슐화되어 있고 `aws_secret_service.py`/`notification_service.py`가 이를 통해 클라이언트를 생성한다 |
| [local-dev.md](../architecture/local-dev.md) | `docs/architecture/local-dev.md`, `examples/docker-compose.yml`, `examples/.env.example`, `examples/localstack/init-ses.sh` | 완전 | Postgres + LocalStack(SES) 조합, healthcheck, 버전 고정 LocalStack 이미지, `app` 서비스(`profiles: [app]`), `.env.example`/`.env.development` 모두 실제 코드로 존재 — 코드와 문서가 일치 |
| [file-storage.md](../architecture/file-storage.md) | `docs/architecture/file-storage.md` | 완전 | Account/Card 도메인에 파일 업로드 기능 자체가 없어 코드 대응은 없음(순수 신규 문서) — `notification_service.py`가 이미 쓰는 `aioboto3` 클라이언트 패턴을 재사용하는 `StorageService` Technical Service로 문서화 |
| [graceful-shutdown.md](../architecture/graceful-shutdown.md) | `docs/architecture/graceful-shutdown.md`, `examples/main.py`의 `lifespan`, `/health/live`·`/health/ready` | 완전 | SIGTERM 수신 시 readiness를 먼저 503으로 전환한 뒤 `engine.dispose()`로 정리하는 종료 순서, `/health/live`·`/health/ready` 엔드포인트 모두 실제 코드로 구현되어 있어 코드와 문서가 일치한다 |
| [observability.md](../architecture/observability.md) | `docs/architecture/observability.md`, `examples/src/common/{logging_config,correlation}.py`, `examples/.../notification_service.py` | 완전 | `JsonFormatter`/`configure_logging()` 기반 구조화 JSON 로깅, `contextvars` 기반 Correlation ID(`main.py`의 `correlation_id_middleware`)가 모두 실제 코드로 존재하고 `notification_service.py`가 `extra={...}`로 구조화된 필드를 전달한다 — 코드와 문서가 일치 |
| [scheduling.md](../architecture/scheduling.md) | `docs/architecture/scheduling.md` | 완전 | 이 저장소에 스케줄링 유스케이스 자체가 없어 코드 대응은 없음(순수 신규 문서) — `BackgroundTasks`/APScheduler/Celery의 트레이드오프를 비교하고 Outbox Relay를 실행하는 예시로 [domain-events.md](../../implementations/fastapi/docs/architecture/domain-events.md)와 연결 |
| [testing.md](../architecture/testing.md) | `docs/architecture/testing.md`, `examples/tests/unit/{domain,application}/`, `examples/tests/test_*_e2e.py` | 완전 | Domain 단위 테스트(`test_account.py`/`test_card.py`/`test_credential.py`/`test_money.py`), Application 단위 테스트(`AsyncMock` 기반 Handler 테스트 다수), E2E 테스트(testcontainers, `httpx.ASGITransport`, account/auth/card/notification 4개 파일) 3단계 모두 실제 코드로 존재 — 코드와 문서가 일치 |
| [conventions.md](../conventions.md) | `docs/architecture/directory-structure.md`(파일명·네이밍 섹션 흡수), `docs/architecture/rate-limiting.md`(Rate Limiting 섹션), `examples/.../interface/rest/account_router.py`, `examples/src/common/rate_limit.py` | 완전 | REST URL 설계(복수 명사, `POST /accounts/{id}/suspend` 등 하위 리소스 경로)는 코드가 루트 규칙을 정확히 따르고, 파일명·클래스명 네이밍 규칙은 `directory-structure.md`에 통합됨. Rate Limiting 원칙도 `slowapi` 기반(전역 `default_limits` + 쓰기 엔드포인트 `@limiter.limit()`)으로 실제 구현되어 있다 |

### 커버리지 요약

- **완전**: 24건 — tactical-ddd, layer-architecture, repository-pattern, persistence, domain-service, domain-events, cqrs-pattern, error-handling, api-response, authentication, cross-cutting-concerns, cross-domain-communication, directory-structure, aggregate-id, container, config, secret-manager, local-dev, file-storage, graceful-shutdown, observability, scheduling, testing, conventions
- **부분**: 0건
- **누락**: 1건 — strategic-ddd (언어 무관 주제, NestJS도 동일)
- **N/A**: 0건

"완전" 판정이라고 해서 `examples/`의 실제 코드가 21개 원칙을 전부 준수한다는 뜻은 아니다 — 위 표의 "코드 격차 (문서에 명시)" 문구가 붙은 항목(repository-pattern, api-response)은 코드 변경이 필요한 실질적 후속 작업이다. 각 문서의 "알려진 격차" 섹션이 그 상세 내용이다. 그 외 항목(domain-events, authentication, cross-cutting-concerns, cross-domain-communication, container, config, local-dev, graceful-shutdown, observability, testing 등)은 이미 코드로 완전히 구현되어 있다.

### harness 검증 범위 참고

`implementations/fastapi/harness/harness.py`는 현재 9개 섹션(file-naming, repository-abc, repository-impl, handler-placement, domain-purity, directory-structure, shared-infra, event-placement, AST 기반 layer-dependency)을 검사하지만, 전부 **파일 배치·네이밍·import 구조**에 대한 정적 검사이며 위 표의 코드 격차(ID 형식, 에러 응답 스키마, Repository 메서드 네이밍 등)는 검증하지 않는다. harness는 이 격차들의 회귀를 잡아주지 않는다 — 코드 개선 작업 시 harness 룰 추가도 함께 검토할 만하다.

---

## FastAPI 전용, 대응 root 문서 없음

- Pydantic `BaseModel`/`EmailStr` 기반 요청·응답 스키마 자동 검증 및 OpenAPI(Swagger) 문서 자동 생성
- `Depends()` 함수형 DI 그래프 — 클래스 데코레이터 없이 provider 함수 조합으로 의존성을 구성 (`account_router.py`의 `_repo`, `_notification_service`)
- `app.dependency_overrides`를 이용한 테스트 환경 의존성 치환 (E2E 테스트에서 `get_session`을 testcontainers 세션으로 교체)
- `implementations/fastapi/docs/architecture/bootstrap.md` — `main.py`의 `FastAPI(...)` 생성, `lifespan`, 라우터/예외 핸들러 등록, FastAPI 자동 OpenAPI(`/docs`)가 NestJS의 명시적 `SwaggerModule.setup()`을 대체하는 방식
- `implementations/fastapi/docs/architecture/cross-domain.md` — Adapter 패턴(ACL)의 FastAPI 구현 예시: Account/Card 두 BC가 실제로 존재해 `application/adapter/`의 ABC + `infrastructure/`의 구현체 + `Depends` 바인딩을 실제 코드로 다룬다 (원칙은 [cross-domain-communication.md](../architecture/cross-domain-communication.md) 참고)
- `implementations/fastapi/docs/architecture/design-principles.md` — 이 저장소 다른 21개 문서를 관통하는 핵심 설계 원칙 13개 요약
- `implementations/fastapi/docs/architecture/module-pattern.md` — DI 컨테이너/모듈 데코레이터가 없는 FastAPI에서 Python 패키지 트리와 `Depends` 팩토리 함수가 NestJS의 `@Module`/`providers`를 대체하는 방식, 순환 import 해소
- `implementations/fastapi/docs/architecture/rate-limiting.md` — `slowapi` 기반 Rate Limiting 구현 가이드 (`examples/`에 실제 구현됨: 전역 `default_limits` + 쓰기 엔드포인트별 `@limiter.limit()`)
- `implementations/fastapi/docs/architecture/shared-modules.md` — 도메인에 속하지 않는 공유 코드(`src/common/`, `src/config/`, `src/auth/`, `src/outbox/`, `src/database.py`)의 위치 규칙 — Account/Card 두 도메인이 실제로 공유한다

---

## FastAPI 선택 이유

- Python 표준 라이브러리의 `ABC`/`abstractmethod`만으로 Repository·Technical Service 인터페이스를 정의할 수 있어, 프레임워크 데코레이터 없이 Domain/Application 순수성을 유지하기 쉽다 (harness의 domain-purity 검사도 이를 전제로 한다).
- Pydantic이 DTO 검증과 직렬화를 하나의 `BaseModel`로 통합해, Interface 계층을 얇게 유지하는 원칙(Interface DTO = thin wrapper)을 자연스럽게 지원한다.
- `async`/`await` 네이티브 지원으로 SQLAlchemy `AsyncSession`, `aioboto3` 등 I/O-바운드 작업을 일관되게 비동기 처리한다.
- `Depends()` 기반 함수형 DI로 Handler 조립이 라우터 코드 안에 명시적으로 드러나, 컨테이너 매직 없이 의존성 흐름을 코드만 보고 추적할 수 있다.

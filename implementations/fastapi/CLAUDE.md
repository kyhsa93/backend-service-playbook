# FastAPI 구현 가이드

DDD 기반 FastAPI(Python) 서버 프로젝트의 설계/구현 가이드이다.
`src/<domain>/{domain,application,interface,infrastructure}/` 4레이어 구조를 따른다.

> **설계 원칙 (프레임워크 무관)** 은 루트의 [CLAUDE.md](../../CLAUDE.md) 및 `../../docs/architecture/`를 참조한다.
> 이 문서는 FastAPI 구현 상세에 집중한다.

## 작업 시 참조할 문서

### 레이어 · 구조

| 작업 / 키워드 | 읽을 문서 |
|---------------|----------|
| 새 도메인 추가, 도메인 모듈 템플릿, Order 예시 | `docs/reference.md` — `scripts/create_domain.py`로 즉시 코드 생성 가능(아래 "스캐폴딩" 참고) |
| 프로젝트 구조, 디렉토리 레이아웃, 파일·클래스 네이밍 | `docs/architecture/directory-structure.md` |
| 레이어 역할, Domain / Application / Interface / Infrastructure, `Depends` 기반 DI | `docs/architecture/layer-architecture.md` |
| Repository 패턴, ABC 인터페이스·SQLAlchemy 구현, 메서드 네이밍 | `docs/architecture/repository-pattern.md` |
| Technical Service, 기술 인프라 서비스 추상화 (`notification_service.py` 패턴) | `docs/architecture/layer-architecture.md` |
| Aggregate, Entity, Value Object, Domain Event, 모델링 방식(dataclass vs 일반 클래스) | `docs/architecture/tactical-ddd.md` |
| Aggregate ID 생성, UUID hex, `.hex` vs 하이픈 | `docs/architecture/aggregate-id.md` |
| 전략적 설계, Subdomain, Bounded Context, Context Map | `../../docs/architecture/strategic-ddd.md` (루트 공용 문서) |
| BC 간 통신 패턴 선택, 동기 vs 비동기, ACL | `../../docs/architecture/cross-domain-communication.md` (루트 공용 문서) |
| 크로스 도메인 호출, Adapter 패턴 구현(ABC + `infrastructure/` 구현체), ACL 예시 코드 | `docs/architecture/cross-domain.md` |
| 핵심 설계 원칙 요약, 13개 규칙 치트시트 | `docs/architecture/design-principles.md` |
| DI 컨테이너 없음, `Depends` 팩토리 = 바인딩 지점, Python 패키지 = 모듈, 순환 import 해소 | `docs/architecture/module-pattern.md` |
| 도메인에 속하지 않는 공유 코드 위치, `src/common/`, `src/config/`, `src/auth/`, `src/outbox/` | `docs/architecture/shared-modules.md` |

### 데이터 / 트랜잭션

| 작업 / 키워드 | 읽을 문서 |
|---------------|----------|
| SQLAlchemy `AsyncSession`, 트랜잭션 경계, Soft Delete | `docs/architecture/persistence.md` |
| Alembic 마이그레이션, `Base.metadata.create_all`의 한계 | `docs/architecture/persistence.md` |
| Domain Event, `pull_events()`, Outbox 패턴, 이벤트 핸들러 멱등성 | `docs/architecture/domain-events.md` |
| CQRS, Command/Query Handler, `execute()`, Bus 도입 기준 | `docs/architecture/cqrs-pattern.md` |

### API / Interface

| 작업 / 키워드 | 읽을 문서 |
|---------------|----------|
| REST 엔드포인트, `APIRouter`, Pydantic 요청/응답 모델 | `docs/architecture/directory-structure.md` |
| API 응답 구조, 페이지네이션, Result 객체, 목록/단건 응답 형식 | `docs/architecture/api-response.md` |
| 인증, JWT, Bearer 토큰, `Depends(get_current_user)` | `docs/architecture/authentication.md` |
| Middleware, Correlation ID, 요청 파이프라인 | `docs/architecture/cross-cutting-concerns.md` |
| 에러 처리, `domain/errors.py`, `@app.exception_handler`, 에러 응답 형식 | `docs/architecture/error-handling.md` |
| Presigned URL, 파일 업로드/다운로드, S3/`aioboto3` | `docs/architecture/file-storage.md` |
| 앱 부트스트랩, `main.py`, `FastAPI(...)` 생성, 라우터/예외 핸들러 등록, Swagger 자동 생성(`/docs`) | `docs/architecture/bootstrap.md` |
| Rate Limiting, `slowapi`, 요청 속도 제한, 429 | `docs/architecture/rate-limiting.md` |

### 운영 / 인프라

| 작업 / 키워드 | 읽을 문서 |
|---------------|----------|
| 환경 설정, `pydantic-settings`, `BaseSettings`, fail-fast 검증 | `docs/architecture/config.md` |
| Secret 관리, AWS Secrets Manager, TTL 캐시 | `docs/architecture/secret-manager.md` |
| `lifespan`, 기동/종료, SIGTERM, 헬스체크 엔드포인트 | `docs/architecture/graceful-shutdown.md` |
| 로컬 개발 환경, `docker-compose.yml`, LocalStack | `docs/architecture/local-dev.md` |
| Dockerfile, 멀티스테이지 빌드, uvicorn | `docs/architecture/container.md` |
| 로깅, 구조화 JSON 로그, Correlation ID, 메트릭/트레이싱 | `docs/architecture/observability.md` |

### 비동기 / 스케줄링

| 작업 / 키워드 | 읽을 문서 |
|---------------|----------|
| 스케줄링, `BackgroundTasks`, APScheduler, Celery 선택 기준 | `docs/architecture/scheduling.md` |
| Outbox Relay, 배치 작업, 멱등성 | `docs/architecture/scheduling.md` |

### 품질 / 검증

| 작업 / 키워드 | 읽을 문서 |
|---------------|----------|
| 테스트 전략, Domain 단위, Application mock 테스트(`unittest.mock`), E2E(testcontainers) | `docs/architecture/testing.md` |
| `pytest-asyncio`, `httpx.ASGITransport`, `dependency_overrides` | `docs/architecture/testing.md` |
| harness 실행, 검사 규칙 목록 | `harness/README.md` |
| harness 설계 원칙(비즈니스 도메인 지식이 아닌 아키텍처 규칙만 평가) | `../../docs/harness.md` (루트 공용 문서) |

## FastAPI 구현 원칙 요약

- 패키지: `src/<domain>/domain/`, `application/{command,query,service}/`, `infrastructure/{persistence,notification}/`, `interface/rest/`
- Repository: `domain/`에 ABC, `infrastructure/`에 SQLAlchemy 구현체
- Technical Service: `application/service/`에 ABC(예: `notification_service.py`), `infrastructure/<concern>/`에 구현체
- CQRS: `XxxHandler` 클래스 + `async def execute(self, cmd/query)` 메서드
- 에러: `domain/errors.py`에 예외 클래스 정의, `main.py`의 `@app.exception_handler`로 HTTP 매핑
- Soft delete: `deleted_at: datetime | None` — hard delete 금지
- DI: FastAPI `Depends`로 Handler/Repository/Service를 라우터에 주입 (전용 DI 컨테이너 없음)

## 구현 검증

```bash
./harness.sh <projectRoot>
```

## Lint / 포맷

`examples/`와 `harness/` 모두 [ruff](https://docs.astral.sh/ruff/)로 린트·포맷을 검사한다(flake8+isort+black 대체).
설정은 `pyproject.toml`(`implementations/fastapi/`) — 규칙셋은 `E`(pycodestyle)/`F`(pyflakes)/`I`(isort),
`line-length = 120`(한글 주석이 많아 100자 기준은 무관한 줄바꿈 diff를 유발하므로 완화).
`harness/tests/fixtures/`는 harness 회귀 테스트용으로 의도적으로 규칙을 위반하는 코드이므로 검사 대상에서 제외한다.

```bash
# implementations/fastapi/ 에서 실행 (examples/requirements.txt에 ruff 포함)
ruff check .           # 린트
ruff check --fix .     # 자동 수정 가능한 항목 고침
ruff format .          # 포맷 적용
ruff format --check .  # 포맷 위반만 확인 (CI와 동일)
```

CI(`.github/workflows/fastapi.yml`)는 `ruff check .`와 `ruff format --check .`를 테스트보다 먼저 실행해 위반 시 실패한다.

## 스캐폴딩 — 새 도메인 생성기

`docs/reference.md`·`examples/src/account`·`examples/src/card`의 실제 코드를 기준으로,
Aggregate(단일 상태 필드 + PENDING/ACTIVE/CANCELLED) + CQRS Command/Query Handler + 도메인
이벤트 1종(cancel에서 발행) + Repository(ABC/구현체) + Router + Pydantic 스키마 + Alembic
마이그레이션까지 한 번에 생성하는 Python 스크립트다. FastAPI에는 DI 컨테이너가 없다 —
eventType → 핸들러 라우팅은 `src/outbox/event_handlers.py`의 `build_event_handlers()` 하나가
프로세스 전체의 단일 조립 지점(composition root)이므로(OutboxPoller/OutboxConsumer가 이를
독립적으로 재사용한다 — domain-events.md 참고), 새 도메인 디렉토리 생성뿐 아니라 `main.py`
(라우터 등록), `event_handlers.py`(공유 composition root인 `build_event_handlers()`),
`migrations/env.py`(Alembic이 감지할 모델 import)까지 함께 배선해야 한다.

```bash
# 기본: ../examples/src/<domain>/ 아래 생성, main.py/event_handlers.py/migrations는
# 건드리지 않고 붙여넣을 스니펫만 출력
python3 scripts/create_domain.py Coupon

# --wire를 주면 main.py/event_handlers.py/migrations/env.py/migrations/versions/까지 자동 배선
python3 scripts/create_domain.py Coupon --wire

# 다른 프로젝트(이 저장소를 템플릿으로 복제한 프로젝트 등)에 생성하려면 --out으로 지정
python3 scripts/create_domain.py Coupon --out /path/to/other-project/src --wire
```

생성 직후 `ruff check . && ruff format --check . && bash harness.sh <projectRoot>`로 검증한다 —
Account/Card와 무관한 새 도메인(Coupon, LoyaltyCategory 등 다단어/불규칙 복수형 포함)으로 실제
테스트해 harness가 `236 passed  PASS`(FAIL 0건)를 확인했다. `--wire` 실행 시 배선 대상 파일에 자동으로
`ruff check --fix`/`ruff format`을 실행해, 도메인 이름에 따라 import 삽입 위치의 알파벳 순서가
달라지는 문제(예: "card" 다음 "common" 앞 vs "database" 다음 "outbox" 앞)를 텍스트 삽입만으로
완벽히 재현하려 하지 않고 ruff의 isort 구현에 맡긴다. 나이브 복수형 규칙(+s/+es/y→ies)을
snake_case 위에서 적용하므로, 진짜 불규칙 복수형(person→people 등) 도메인이면
`find<Domains>`/`<domains>` 등 생성된 이름을 수동으로 다듬어야 할 수 있다. 생성되는 것은
구조적 스켈레톤(빈 CRUD형 시작점)이라, 실제 비즈니스 규칙·에러 메시지·필드는 생성 후 직접
채워 넣는다.

## 예시 코드

`examples/` 디렉토리에 Account 도메인 전체 구현 예시가 있다.
새 도메인 작업 시 이를 템플릿으로 사용한다 (도메인명만 변경).

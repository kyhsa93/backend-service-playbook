# FastAPI 구현 가이드

DDD 기반 FastAPI(Python) 서버 프로젝트의 설계/구현 가이드이다.
`src/<domain>/{domain,application,interface,infrastructure}/` 4레이어 구조를 따른다.

> **설계 원칙 (프레임워크 무관)** 은 루트의 [CLAUDE.md](../../CLAUDE.md) 및 `../../docs/architecture/`를 참조한다.
> 이 문서는 FastAPI 구현 상세에 집중한다.

## 작업 시 참조할 문서

### 레이어 · 구조

| 작업 / 키워드 | 읽을 문서 |
|---------------|----------|
| 프로젝트 구조, 디렉토리 레이아웃, 파일·클래스 네이밍 | `docs/architecture/directory-structure.md` |
| 레이어 역할, Domain / Application / Interface / Infrastructure, `Depends` 기반 DI | `docs/architecture/layer-architecture.md` |
| Repository 패턴, ABC 인터페이스·SQLAlchemy 구현, 메서드 네이밍 | `docs/architecture/repository-pattern.md` |
| Technical Service, 기술 인프라 서비스 추상화 (`notification_service.py` 패턴) | `docs/architecture/layer-architecture.md` |
| Aggregate, Entity, Value Object, Domain Event, 모델링 방식(dataclass vs 일반 클래스) | `docs/architecture/tactical-ddd.md` |
| Aggregate ID 생성, UUID hex, `.hex` vs 하이픈 | `docs/architecture/aggregate-id.md` |
| 전략적 설계, Subdomain, Bounded Context, Context Map | `../../docs/architecture/strategic-ddd.md` (루트 공용 문서) |
| BC 간 통신 패턴 선택, 동기 vs 비동기, ACL | `../../docs/architecture/cross-domain-communication.md` (루트 공용 문서) |

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
| harness 실행, evaluator 규칙 목록 | `harness/README.md` |

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

## 예시 코드

`examples/` 디렉토리에 Account 도메인 전체 구현 예시가 있다.
새 도메인 작업 시 이를 템플릿으로 사용한다 (도메인명만 변경).

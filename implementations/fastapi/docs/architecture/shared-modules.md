# 공유 코드 구조

## 현재 상태 — `common/`, `config/`, `auth/`, `outbox/`가 이미 도메인 바깥의 공유 코드로 존재한다

이 저장소의 `examples/src/` 트리를 실제로 확인하면 `account/` 패키지 바깥에 다음이 있다.

```
examples/src/
  __init__.py
  database.py            ← 공유 인프라: 엔진/세션 팩토리, get_session() 의존성. DatabaseConfig().url로 조립
  common/                 ← 공유 인프라: 여러 도메인이 재사용하는 순수 유틸/인프라
    generate_id.py         ← generate_id() — uuid.uuid4().hex 기반 ID 생성 (aggregate-id.md)
    logging_config.py      ← JsonFormatter, configure_logging() (observability.md)
    correlation.py         ← contextvars 기반 Correlation ID (cross-cutting-concerns.md)
    secret_service.py      ← SecretService ABC (secret-manager.md)
    aws_secret_service.py  ← AwsSecretService(SecretService) 구현체 (secret-manager.md)
  config/                  ← 관심사별 설정 클래스 (config.md)
    database_config.py     ← DatabaseConfig(BaseSettings) — DATABASE_URL 필수값 검증
    validator.py            ← validate_env() — 모듈 임포트 시점에 호출되는 fail-fast 진입점
  auth/                    ← 공유 인증 (authentication.md) — account/처럼 4레이어 구조를 그대로 따른다
    domain/
      errors.py             ← InvalidTokenError
      token.py              ← TokenPayload
    application/
      service/
        auth_service.py     ← AuthService ABC (Technical Service 인터페이스)
    infrastructure/
      jwt_auth_service.py   ← JwtAuthService(AuthService), set_jwt_secret()
    interface/
      rest/
        auth_router.py      ← POST /auth/sign-in
        dependencies.py     ← get_current_user(), CurrentUser
        schemas.py
  outbox/                  ← 공유 인프라: Outbox 패턴 (domain-events.md 참조)
    outbox_model.py       ← OutboxModel(Base) — account_repository.py의 Base를 그대로 재사용
    outbox_writer.py       ← OutboxWriter — Repository.save()가 같은 세션에서 호출
    outbox_poller.py       ← OutboxPoller — Outbox → SQS 발행, main.py의 lifespan이 백그라운드 task로 기동
    outbox_consumer.py     ← OutboxConsumer — SQS → EventHandler 수신, 마찬가지로 백그라운드 task
    event_handlers.py      ← build_event_handlers() — eventType → 핸들러 dict 조립(composition root)
  account/                ← 1번째 도메인 (Bounded Context)
    domain/
    application/
      event/               ← EventHandler — event_type별로 하나씩, Outbox 페이로드를 역직렬화해 NotificationService 호출
    infrastructure/
    interface/
  card/                   ← 2번째 도메인 (Bounded Context) — account와 Integration Event로 통신
    domain/ application/ infrastructure/ interface/
  payment/                ← 3번째 도메인 (Bounded Context) — Payment/Refund
    domain/ application/ infrastructure/ interface/
    domain/refund_eligibility_service.py   ← 여러 Aggregate(Payment/Refund)를 조율하는 Domain Service — domain-service.md 참고
```

`outbox/`는 도메인에 속하지 않는 순수 기술적 관심사(Outbox 테이블 관리, 이벤트 드레인)이므로 어느 도메인 패키지 안이 아니라 이 최상위 공유 위치에 두고, Account/Card/Payment 세 도메인이 모두 같은 인스턴스를 공유한다. `common/`, `config/`, `auth/`도 같은 이유로 도메인 바깥에 있다 — 특정 도메인의 비즈니스 규칙과 무관한 순수 기술/횡단 관심사이기 때문이다.

```python
# src/database.py — 실제 코드, 도메인에 속하지 않는 공유 인프라
from collections.abc import AsyncGenerator

from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from .config.database_config import DatabaseConfig

engine = create_async_engine(DatabaseConfig().url, echo=False)  # type: ignore[call-arg]
SessionLocal = async_sessionmaker(engine, expire_on_commit=False)


async def get_session() -> AsyncGenerator[AsyncSession, None]:
    async with SessionLocal() as session:
        yield session
        await session.commit()
```

`database.py`가 모듈 파일 하나로 충분한 이유는 Account/Card/Payment 세 도메인 모두 같은 `engine`/`SessionLocal`을 공유하기 때문이다 — 도메인이 늘어나도 트랜잭션 경계를 도메인별로 분리해야 할 시나리오가 아직 없어 별도 패키지로 쪼개지 않았다([directory-structure.md](directory-structure.md)의 "공용 인프라 배치 기준" 절 참고). NestJS 구현은 같은 역할을 `src/database/`라는 별도 패키지(`DatabaseModule`, `@Global()`)로 분리한다 — FastAPI에는 "전역으로 등록"하는 모듈 개념 자체가 없으므로, `engine`/`SessionLocal`을 모듈 최상위 변수로 두고 어디서든 import하는 것 자체가 이미 "전역 공유"다.

## `auth/`는 flat 구조가 아니라 `account/`와 동일한 4레이어 구조를 따른다

이 절은 예전에 `auth/jwt_service.py`, `auth/dependencies.py`처럼 flat한 구조를 제안했었다. **실제로 구현된 `src/auth/`는 그렇지 않다** — 도메인 패키지(`account/`)와 동일하게 `domain/`, `application/service/`, `infrastructure/`, `interface/rest/` 4레이어로 나뉘어 있다(위 "현재 상태" 트리 참조). 공유 코드라고 해서 레이어 분리 원칙이 느슨해지지 않는다 — `auth/`도 하나의 작은 Bounded Context처럼 조직된다.

## 도메인이 늘어날 때의 구조 — Card/Payment로 이미 확인됨

`card/`(2번째 도메인), `payment/`(3번째 도메인)가 추가된 뒤에도 `database.py`/`common/`/`config/`/`auth/`/`outbox/`는 그대로 공유되고, 새 도메인은 `account/`와 나란히 같은 4레이어 패키지로 추가되었다 — 예상이 아니라 실제로 이렇게 되었다.

```
src/
  database.py                        ← 유지 — 엔진은 하나(연결 풀 공유)
  common/                            ← 두 번째 도메인도 그대로 재사용
  config/
  auth/                              ← account/와 동일한 4레이어 구조
  outbox/                            ← 두 번째 도메인도 같은 Outbox 테이블/Poller/Consumer를 공유
  account/                           ← 도메인 패키지
    domain/ application/ infrastructure/ interface/
  user/                              ← 두 번째 도메인 패키지 (가상)
    domain/ application/ infrastructure/ interface/
```

## 판단 기준 — 어떤 코드가 `src/common/`(또는 `config/`, `auth/`, `outbox/`)로 가는가

- **어느 한 도메인에도 속하지 않는다**: `Account`나 `User`의 비즈니스 규칙과 무관하게, 순수 기술적/횡단적 관심사다.
- **둘 이상의 도메인이 실제로 재사용한다**: 지금 당장 하나만 써도 향후 재사용이 명확히 예정된 것(예: ID 생성기, 에러 응답 빌더)은 미리 분리해도 좋다 — 반대로 Account 전용 로직을 미리 `common/`에 두면 과도한 일반화(premature abstraction)가 된다.
- **Domain 레이어가 참조해서는 안 된다**: `src/common/`이라도 `logging`, `contextvars`, `fastapi` 등을 사용하는 코드는 `domain/`에서 import할 수 없다 — [cross-cutting-concerns.md](cross-cutting-concerns.md)의 "Domain 레이어에서 미들웨어/로거 사용 금지" 원칙이 `src/common/`에도 그대로 적용된다.

## NestJS의 `@Global()` 모듈과의 차이

NestJS는 `database/`, `outbox/`, `auth/`를 `@Global()` 모듈로 선언해 다른 모듈이 `imports` 없이 바로 주입받을 수 있게 한다([nestjs shared-modules.md](../../../nestjs/docs/architecture/shared-modules.md) 참조). FastAPI에는 "전역으로 등록"하는 절차 자체가 없다 — Python에서 어떤 모듈이든 import하면 바로 쓸 수 있기 때문에, `@Global()`이 풀던 문제("이 모듈을 쓰려는 곳마다 매번 imports에 추가해야 하는 번거로움")가 애초에 존재하지 않는다. 대신 각 도메인의 `interface/rest/*_router.py`가 필요한 공유 코드를 직접 import하고, `Depends` 팩토리 안에서 조합한다.

```python
# src/account/interface/rest/account_router.py — 실제 코드. 공유 코드를 직접 import해서 조합
from ....auth.interface.rest.dependencies import CurrentUser, get_current_user  # src/auth/ 공유 모듈
from ....database import get_session                                            # 공유 인프라
```

---

### 관련 문서

- [directory-structure.md](directory-structure.md) — "공용 인프라 배치 기준" 절, 전체 패키지 트리
- [module-pattern.md](module-pattern.md) — Python 패키지가 NestJS 모듈을 대체하는 방식 전반
- [aggregate-id.md](aggregate-id.md) — `common/generate_id.py`
- [cross-cutting-concerns.md](cross-cutting-concerns.md) — `common/correlation.py`, Domain 레이어 참조 금지 원칙
- [secret-manager.md](secret-manager.md) — `common/secret_service.py`/`common/aws_secret_service.py`
- [authentication.md](authentication.md) — `auth/` 4레이어 구조 상세
- [domain-events.md](domain-events.md) — `outbox/` 공유 패키지의 실제 구현(`outbox_model.py`/`outbox_writer.py`/`outbox_poller.py`/`outbox_consumer.py`/`event_handlers.py`)

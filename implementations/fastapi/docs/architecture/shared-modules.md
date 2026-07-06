# 공유 코드 구조

## 현재 상태 — 도메인이 하나뿐이라 공유 코드가 `src/database.py` 하나다

이 저장소의 `examples/src/` 트리를 실제로 확인하면 `account/` 패키지 바깥에 있는 것은 다음 하나뿐이다.

```
examples/src/
  __init__.py
  database.py            ← 유일한 공유 인프라: 엔진/세션 팩토리, get_session() 의존성
  account/                ← 유일한 도메인 (Bounded Context)
    domain/
    application/
    infrastructure/
    interface/
```

```python
# src/database.py — 실제 코드, 도메인에 속하지 않는 공유 인프라
import os
from collections.abc import AsyncGenerator

from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

DATABASE_URL = os.getenv(
    "DATABASE_URL", "postgresql+asyncpg://postgres:postgres@localhost:5432/account"
)

engine = create_async_engine(DATABASE_URL, echo=False)
SessionLocal = async_sessionmaker(engine, expire_on_commit=False)


async def get_session() -> AsyncGenerator[AsyncSession, None]:
    async with SessionLocal() as session:
        yield session
        await session.commit()
```

`database.py`가 모듈 파일 하나로 충분한 이유는 도메인이 Account 하나뿐이기 때문이다 — [directory-structure.md](directory-structure.md)의 "공용 인프라 배치 기준" 절이 이미 이 점을 명시하고 있다. NestJS 구현은 같은 역할을 `src/database/`라는 별도 패키지(`DatabaseModule`, `@Global()`)로 분리한다 — FastAPI에는 "전역으로 등록"하는 모듈 개념 자체가 없으므로, `engine`/`SessionLocal`을 모듈 최상위 변수로 두고 어디서든 import하는 것 자체가 이미 "전역 공유"다.

## 두 번째 도메인이 추가될 때의 권장 구조

도메인이 둘 이상이 되면 `database.py` 하나로는 부족해지는 지점들이 생긴다 — 여러 도메인이 공유하는 유틸(에러 응답 빌더, ID 생성기), 인증, Outbox가 그 예다. 아래는 이 저장소의 명명 규칙([directory-structure.md](directory-structure.md)의 "파일명·모듈 네이밍" 절)을 그대로 적용한 권장 구조다.

```
src/
  database.py                        ← 유지 — 도메인이 늘어도 엔진은 하나(연결 풀 공유)라 패키지로 승격할 필요는 낮음
  common/                            ← 신설 제안: 여러 도메인이 공유하는 순수 유틸
    generate_id.py                   ← uuid.uuid4().hex 기반 ID 생성 (aggregate-id.md)
    error_response.py                ← statusCode/code/message/error 표준 응답 빌더 (error-handling.md)
    correlation.py                   ← contextvars 기반 Correlation ID (cross-cutting-concerns.md)
  config/                            ← 신설 제안: 관심사별 설정 클래스 (config.md)
    database_config.py
    jwt_config.py
  auth/                              ← 신설 제안: 여러 도메인이 공유하는 인증 (authentication.md)
    jwt_service.py
    dependencies.py                  ← get_current_user() — Depends로 라우터에서 공유
  outbox/                            ← 신설 제안: 여러 도메인이 공유하는 Outbox (domain-events.md)
    outbox_model.py
    outbox_relay.py
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
# src/account/interface/rest/account_router.py — 공유 코드를 직접 import해서 조합
from src.auth.dependencies import get_current_user   # src/auth/ 공유 모듈
from src.common.correlation import get_correlation_id  # src/common/ 공유 모듈
from src.database import get_session                   # 이미 존재하는 공유 인프라
```

---

### 관련 문서

- [directory-structure.md](directory-structure.md) — "공용 인프라 배치 기준" 절, 전체 패키지 트리
- [module-pattern.md](module-pattern.md) — Python 패키지가 NestJS 모듈을 대체하는 방식 전반
- [aggregate-id.md](aggregate-id.md) — `common/generate_id.py` 후보
- [error-handling.md](error-handling.md) — `common/error_response.py` 후보
- [cross-cutting-concerns.md](cross-cutting-concerns.md) — `common/correlation.py` 후보, Domain 레이어 참조 금지 원칙
- [domain-events.md](domain-events.md) — `outbox/` 공유 패키지 후보

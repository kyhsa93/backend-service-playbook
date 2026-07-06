# 모듈 구성 패턴 — DI 컨테이너 없이 `Depends`로

FastAPI에는 NestJS의 `@Module`/DI 컨테이너에 해당하는 것이 없다. 이 문서는 FastAPI가 그 자리를 무엇으로 대체하는지 세 가지로 나눠 설명한다 — ① 패키지 트리가 "모듈 경계"를 대신하는 방식, ② `Depends` 팩토리 함수가 "바인딩"을 대신하는 방식, ③ `APIRouter`가 "컨트롤러 등록"을 대신하는 방식.

## ① Python 패키지 트리 = NestJS의 "모듈"

NestJS는 `@Module({ providers, controllers, exports })` 데코레이터로 한 Bounded Context의 경계를 명시적으로 선언한다. FastAPI에는 이 선언이 없다 — 대신 **패키지 디렉토리 자체가 경계**다.

```
src/
  account/                    ← "AccountModule"에 대응 — 별도 선언 없이 디렉토리가 곧 경계
    domain/
    application/
    infrastructure/
    interface/
  database.py                 ← 공유 인프라 (shared-modules.md 참조)
```

- **1 Bounded Context = 1 최상위 패키지**(`src/account/`)라는 원칙은 NestJS와 동일하다 — 다른 것은 "이 경계를 선언하는 문법이 있는가"뿐이다.
- NestJS의 `exports: [UserService]`(다른 모듈에 공개할 provider 선택)에 대응하는 메커니즘이 없다 — Python은 `from src.account.application... import X`로 무엇이든 import할 수 있다. 경계는 **컨벤션**으로 지켜진다: 다른 패키지의 `domain/`, `infrastructure/` 내부를 직접 import하지 않고, `application/` 계층의 Handler/Adapter만 외부 진입점으로 사용한다([cross-domain.md](cross-domain.md) 참조).

## ② `Depends` 팩토리 함수 = NestJS의 `{ provide, useClass }`

NestJS는 모듈의 `providers` 배열에서 인터페이스(abstract class) ↔ 구현체를 선언적으로 바인딩한다. FastAPI는 이 바인딩을 **팩토리 함수를 작성하는 것**으로 대체한다 — 이미 이 저장소가 쓰고 있는 패턴이다.

```python
# src/account/interface/rest/account_router.py — 실제 코드
def _repo(session: AsyncSession = Depends(get_session)) -> SqlAlchemyAccountRepository:
    return SqlAlchemyAccountRepository(session)


def _notification_service(session: AsyncSession = Depends(get_session)) -> NotificationService:
    return SesNotificationService(session)
```

| NestJS | FastAPI |
|--------|---------|
| `{ provide: AccountRepository, useClass: SqlAlchemyAccountRepository }`를 모듈 `providers`에 선언 | `_repo()` 팩토리 함수를 정의하고 라우트 시그니처에서 `Depends(_repo)`로 참조 |
| 컨테이너가 생성자 인자를 보고 자동으로 의존성 그래프를 구성(`constructor(private readonly repo: AccountRepository)`) | 팩토리 함수의 인자를 FastAPI가 재귀적으로 해석 — `_repo`가 `Depends(get_session)`을 요구하면 `get_session()`을 먼저 resolve한다 |
| 바인딩이 **모듈 선언 파일**에 흩어져 있다 | 바인딩이 **팩토리 함수 정의 자체**다 — "어디서 바인딩하는가"를 찾으려면 팩토리 함수를 grep하면 된다 |
| Scope(`REQUEST`, `SINGLETON`) 를 데코레이터로 지정 | `Depends`는 기본적으로 **요청 스코프**(같은 요청 안에서 동일 파라미터 조합이면 캐싱됨). 싱글턴이 필요하면 모듈 레벨 전역 변수(`engine`, `SessionLocal`이 이미 이 패턴)로 만든다 |

라우트 함수 파라미터의 타입 힌트는 ABC(`NotificationService`)로 선언하지만, 실제 주입되는 값은 팩토리가 반환하는 구현체(`SesNotificationService`)다 — [layer-architecture.md](layer-architecture.md)의 "Infrastructure 레이어" 절 참조.

## ③ `APIRouter` 조합 = NestJS의 `controllers` 등록

```python
# src/account/interface/rest/account_router.py
router = APIRouter(prefix="/accounts", tags=["Account"])

@router.post("", status_code=201, ...)
async def create_account(...): ...
```

```python
# main.py — 앱 최상위에서 라우터를 조합
app.include_router(account_router)
```

두 번째 도메인이 생기면 `app.include_router(user_router, prefix="/users")` 한 줄을 추가하는 것으로 끝난다 — NestJS의 `AppModule`이 `imports: [AccountModule, UserModule]`로 도메인 모듈을 조합하는 것과 개념적으로 동일한 지점이지만, FastAPI에는 이 조합을 위한 전용 클래스(`AppModule`)가 없고 `main.py` 자체가 그 역할을 겸한다. 상세: [bootstrap.md](bootstrap.md).

## Python의 순환 참조 — NestJS와 다른 방식으로 발생하고, 다른 방식으로 푼다

NestJS는 모듈 A가 모듈 B를 `imports`하고 B가 다시 A를 `imports`하면 순환 의존이 발생하고, `forwardRef()`로 우회하거나 설계를 재검토해야 한다. Python은 데코레이터 기반 모듈 그래프가 없는 대신, **모듈 최상위(top-level) `import` 문 자체가 서로를 가리킬 때** 순환 import가 발생한다 — 이는 프레임워크 문제가 아니라 언어 차원의 문제다.

```python
# src/account/application/adapter/user_adapter.py
from src.user.domain.user import User  # ← user 패키지를 top-level에서 import

# src/user/application/adapter/account_adapter.py
from src.account.domain.account import Account  # ← account 패키지를 top-level에서 import
# 두 파일이 서로를 import하면 ImportError: cannot import name ... (partially initialized module)
```

**해결 우선순위:**

1. **먼저 경계를 재검토한다** — 두 도메인이 서로를 직접 참조해야 한다면 Bounded Context 분리가 잘못되었을 가능성이 크다. [cross-domain.md](cross-domain.md)의 Adapter 패턴으로 한쪽 방향의 직접 의존을 없앨 수 있는지 먼저 확인한다.
2. **타입 힌트만 필요하면 `TYPE_CHECKING`으로 지연**한다 — 런타임에는 import되지 않으므로 순환이 발생하지 않는다.

   ```python
   from typing import TYPE_CHECKING

   if TYPE_CHECKING:
       from src.user.domain.user import User  # 타입 체커만 이 import를 본다

   class UserAdapter(ABC):
       @abstractmethod
       async def find_by_id(self, user_id: str) -> "User | None": ...
   ```

3. **런타임에 실제로 값이 필요하면 함수/메서드 내부로 import를 옮긴다** — 모듈 최상위가 아니라 호출 시점에 import하면 그 시점엔 양쪽 모듈이 이미 완전히 로드되어 있다.

   ```python
   def build_user_adapter() -> UserAdapter:
       from src.user.application.query.get_user_handler import GetUserHandler  # 지연 import
       return InProcessUserAdapter(GetUserHandler(...))
   ```

NestJS의 `forwardRef()`가 "일단 우회하고 넘어가는" 성격이 강한 반면, Python의 지연 import는 명시적으로 코드에 드러나므로 나중에 "왜 여기서 함수 내부 import를 썼는가"를 추적하기 쉽다는 차이가 있다 — 그렇다고 순환 자체가 정당화되는 것은 아니며, 1번(경계 재검토)이 항상 우선한다.

---

### 관련 문서

- [bootstrap.md](bootstrap.md) — `main.py`에서 라우터를 조합하는 지점
- [layer-architecture.md](layer-architecture.md) — `Depends` 팩토리가 ABC ↔ 구현체를 바인딩하는 상세 예시
- [cross-domain.md](cross-domain.md) — 도메인 간 Adapter 패턴, 직접 import를 피하는 이유
- [directory-structure.md](directory-structure.md) — 패키지 트리 전체 구조
- [shared-modules.md](shared-modules.md) — 도메인에 속하지 않는 공유 코드의 위치

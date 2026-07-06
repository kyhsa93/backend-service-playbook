# 테스트 전략

> 프레임워크 무관 원칙: [../../../../docs/architecture/testing.md](../../../../docs/architecture/testing.md)

root는 3개 레이어(Domain 단위, Application 단위, E2E)를 요구한다.

| 레이어 | 검증 범위 | 의존성 전략 | 이 저장소의 현재 상태 |
|--------|----------|------------|----------------------|
| Domain 단위 테스트 | Aggregate, Value Object | 프레임워크 없음 | **없음 — 격차** |
| Application 단위 테스트 | Handler의 조율 로직 | Repository/Service를 mock | **없음 — 격차** |
| E2E 테스트 | Interface→Application→Infrastructure 전체 | 실제 DB(testcontainers) | `tests/test_account_e2e.py`, `tests/test_notification_e2e.py` — 잘 구현됨 |

## 알려진 격차 — Domain/Application 단위 테스트가 없다

`tests/`에는 E2E 테스트 두 개뿐이다. E2E는 testcontainers로 실제 Postgres/LocalStack까지 띄우므로 실행이 느리고(컨테이너 기동 시간), `Account.deposit()`의 불변식 하나를 검증하려고 매번 HTTP 요청 + DB 왕복을 거쳐야 한다. 이 문서 작성 시점 기준 이 격차는 코드에 남아 있다. 아래는 pytest 기반으로 이 저장소에 추가해야 할 나머지 두 레이어다.

---

## Domain 단위 테스트 — 프레임워크 없이 `Account` 자체를 검증

**위치 제안**: `tests/unit/domain/test_account.py` (또는 root 컨벤션처럼 소스 옆에 `src/account/domain/test_account.py`)

```python
# tests/unit/domain/test_account.py
import pytest

from src.account.domain.account import Account
from src.account.domain.account_status import AccountStatus
from src.account.domain.errors import (
    DepositRequiresActiveAccountError,
    InsufficientBalanceError,
    InvalidAmountError,
)
from src.account.domain.events import AccountCreated, MoneyDeposited


def make_active_account(currency: str = "KRW") -> Account:
    return Account.create(owner_id="owner-1", currency=currency, email="owner1@example.com")


def test_create_계좌_생성_시_AccountCreated_이벤트가_수집된다() -> None:
    account = make_active_account()

    events = account.pull_events()

    assert len(events) == 1
    assert isinstance(events[0], AccountCreated)
    assert events[0].owner_id == "owner-1"


def test_deposit_0원_이하_입금_시_InvalidAmountError를_던진다() -> None:
    account = make_active_account()
    account.pull_events()   # 생성 이벤트 소진

    with pytest.raises(InvalidAmountError):
        account.deposit(0)


def test_deposit_정지된_계좌에_입금_시_에러를_던진다() -> None:
    account = make_active_account()
    account.suspend()

    with pytest.raises(DepositRequiresActiveAccountError):
        account.deposit(1000)


def test_deposit_성공_시_잔액이_증가하고_MoneyDeposited_이벤트가_수집된다() -> None:
    account = make_active_account()
    account.pull_events()

    account.deposit(10000)

    assert account.balance.amount == 10000
    events = account.pull_events()
    assert any(isinstance(e, MoneyDeposited) for e in events)


def test_withdraw_잔액_부족_시_InsufficientBalanceError를_던진다() -> None:
    account = make_active_account()

    with pytest.raises(InsufficientBalanceError):
        account.withdraw(1000)   # 잔액 0인 계좌에서 출금


def test_close_잔액이_0이_아니면_종료할_수_없다() -> None:
    account = make_active_account()
    account.deposit(5000)

    from src.account.domain.errors import AccountBalanceNotZeroError

    with pytest.raises(AccountBalanceNotZeroError):
        account.close()
```

**원칙:**
- `Account.create()`처럼 이미 있는 팩토리를 테스트 픽스처로 재사용한다 (별도 mock/stub 불필요 — Domain 객체는 프레임워크 의존이 없으므로 그대로 인스턴스화할 수 있다).
- 어떤 것도 mock하지 않는다 — Aggregate는 순수 객체이므로 실제 인스턴스로 직접 검증한다.
- 에러 검증은 `pytest.raises(SpecificErrorClass)`로 예외 타입을 명시한다 (문자열 메시지 비교 금지 — [error-handling.md](error-handling.md)의 타입화 원칙과 일치).

---

## Application 단위 테스트 — Repository/Service를 mock

**위치 제안**: `tests/unit/application/test_deposit_handler.py`

Handler 생성자가 ABC(`AccountRepository`, `NotificationService`) 타입을 받으므로, `unittest.mock`으로 만든 mock 객체를 그대로 주입할 수 있다 — 실제 DB/SES 없이 조율 로직만 검증한다.

```python
# tests/unit/application/test_deposit_handler.py
from unittest.mock import AsyncMock

import pytest

from src.account.application.command.deposit_handler import DepositCommand, DepositHandler
from src.account.domain.account import Account
from src.account.domain.errors import AccountNotFoundError


@pytest.fixture
def repo() -> AsyncMock:
    return AsyncMock()   # AccountRepository의 모든 async 메서드를 자동으로 mock


@pytest.fixture
def notification_service() -> AsyncMock:
    return AsyncMock()


@pytest.mark.asyncio
async def test_execute_계좌가_없으면_AccountNotFoundError를_던진다(repo, notification_service) -> None:
    repo.find_by_id.return_value = None
    handler = DepositHandler(repo, notification_service)

    with pytest.raises(AccountNotFoundError):
        await handler.execute(DepositCommand(account_id="non-existent", requester_id="owner-1", amount=1000))

    notification_service.notify.assert_not_called()   # 조회 실패 시 알림도 발송되지 않아야 한다


@pytest.mark.asyncio
async def test_execute_입금_성공_시_save와_notify가_호출된다(repo, notification_service) -> None:
    account = Account.create(owner_id="owner-1", currency="KRW", email="owner1@example.com")
    account.pull_events()   # 생성 이벤트 소진 — deposit 이벤트만 남긴다
    repo.find_by_id.return_value = account
    handler = DepositHandler(repo, notification_service)

    transaction = await handler.execute(
        DepositCommand(account_id=account.account_id, requester_id="owner-1", amount=10000)
    )

    assert transaction.type == "DEPOSIT"
    repo.save.assert_awaited_once_with(account)
    notification_service.notify.assert_awaited_once()   # MoneyDeposited 이벤트가 notify로 전달됨
```

**원칙:**
- `unittest.mock.AsyncMock`(표준 라이브러리, Python 3.8+)이 `async def` 메서드를 자동으로 mock한다 — 별도 `pytest-mock` 없이도 충분하지만, fixture 조합이 많아지면 `pytest-mock`의 `mocker` fixture로 정리해도 좋다.
- mock은 ABC(`AccountRepository`)의 메서드 시그니처를 따른다 — `AsyncMock()`은 스펙 없이 모든 속성을 mock하므로, 실수 방지를 원하면 `AsyncMock(spec=AccountRepository)`로 시그니처를 강제한다.
- 검증 대상은 **조율 흐름**(조회 → 도메인 메서드 호출 → 저장 → 알림)이지 비즈니스 규칙이 아니다 — 비즈니스 규칙은 Domain 단위 테스트에서 이미 검증했다.

---

## E2E 테스트 — 이미 잘 구현됨

`tests/test_account_e2e.py`, `tests/test_notification_e2e.py`가 testcontainers로 실제 Postgres(+ SES 케이스는 LocalStack)를 띄워 HTTP 엔드포인트 전체 경로를 검증한다.

```python
# tests/test_account_e2e.py
@pytest_asyncio.fixture(scope="session")
async def client() -> AsyncGenerator[AsyncClient, None]:
    with PostgresContainer("postgres:16-alpine") as postgres:
        url = postgres.get_connection_url().replace("postgresql+psycopg2", "postgresql+asyncpg")
        engine = create_async_engine(url)
        async with engine.begin() as conn:
            await conn.run_sync(Base.metadata.create_all)
        ...
        app.dependency_overrides[get_session] = override_get_session
        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://test") as ac:
            yield ac
```

**이미 지켜지고 있는 원칙:**
- `app.dependency_overrides[get_session]`로 실제 세션 팩토리를 testcontainers DB로 교체 — 프로덕션 코드를 수정하지 않고 의존성만 대체한다.
- `httpx.ASGITransport`로 실제 네트워크 소켓 없이 ASGI 앱을 직접 호출 — 빠르면서도 진짜 HTTP 요청/미들웨어 경로를 통과한다.
- 각 테스트가 독립적인 계좌를 생성해 사용하므로 테스트 간 상태 공유가 없다.
- `test_notification_e2e.py`는 `LocalStackContainer`로 실제 SES 발송까지 확인한다 — mock이 아닌 실제 발송 프로토콜을 검증한다.

`pytest.ini`의 `asyncio_mode = auto`로 모든 `async def test_*`가 자동으로 `pytest-asyncio` 처리 대상이 된다.

---

## 테스트 파일 배치 (확장 후)

```
implementations/fastapi/examples/
  src/
    account/
      domain/
        account.py
  tests/
    unit/
      domain/
        test_account.py               ← Domain 단위 테스트 (신설 제안)
      application/
        test_deposit_handler.py       ← Application 단위 테스트 (신설 제안)
    test_account_e2e.py               ← E2E (기존)
    test_notification_e2e.py          ← E2E (기존)
```

---

## 테스트 네이밍 패턴

```
test_<메서드>_<조건>_<기대_결과>
예: test_withdraw_잔액_부족_시_InsufficientBalanceError를_던진다
```

기존 E2E 테스트(`test_deposit_success`, `test_deposit_account_not_found_returns_404`)의 네이밍과 일관되게, 한글 조건 설명을 붙여도 무방하다 — 이 저장소는 이미 함수명에 한글을 섞는 스타일을 쓰고 있지 않으므로 새 단위 테스트를 추가할 때는 기존 E2E 파일의 영어 스타일(`test_<행위>_<조건>_returns_<결과>`)과 맞출지, 위 예시의 한글 서술형으로 갈지 팀에서 통일한다.

---

## 원칙

- **3개 레이어를 모두 갖춘다**: Domain 단위(빠름, mock 없음), Application 단위(mock으로 조율 검증), E2E(실제 DB로 통합 검증).
- **Domain 테스트는 mock하지 않는다**: Aggregate는 순수 객체이므로 그대로 인스턴스화한다.
- **Application 테스트는 ABC를 mock한다**: `AsyncMock(spec=AccountRepository)`로 시그니처를 강제한다.
- **E2E는 testcontainers로 실제 인프라를 검증한다**: 이미 잘 지켜지고 있다.
- **에러 검증은 타입으로**: `pytest.raises(SpecificError)` — 메시지 문자열 비교 금지.

---

### 관련 문서

- [tactical-ddd.md](tactical-ddd.md) — Domain 레이어 설계 (단위 테스트 대상)
- [layer-architecture.md](layer-architecture.md) — Application Handler (Application 단위 테스트 대상)
- [error-handling.md](error-handling.md) — 에러 타입 검증
- [local-dev.md](local-dev.md) — testcontainers가 사용하는 LocalStack/Postgres 이미지

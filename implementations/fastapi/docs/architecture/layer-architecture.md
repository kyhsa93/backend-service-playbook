# 레이어 아키텍처

> 프레임워크 무관 원칙: [../../../../docs/architecture/layer-architecture.md](../../../../docs/architecture/layer-architecture.md)

## 의존 방향

```
Interface (APIRouter)  →  Application (Handler)  →  Domain (Aggregate, Repository ABC)
                                                          ↑
                                                   Infrastructure (Repository 구현체, Technical Service 구현체)
```

- 상위 레이어는 하위 레이어에 의존할 수 있지만, 하위 레이어는 상위 레이어에 의존하지 않는다.
- Domain 레이어는 어떤 레이어에도 의존하지 않는다 — `fastapi`, `sqlalchemy`, `aioboto3` import 없음.
- Infrastructure 레이어는 Domain/Application의 ABC를 구현한다 (의존성 역전) — FastAPI에는 전용 DI 컨테이너가 없으므로, `Depends`의 팩토리 함수가 이 바인딩을 담당한다.

이 저장소의 실제 코드로 각 레이어를 살펴본다 (`examples/src/account/`).

---

## Domain 레이어 — `domain/`

비즈니스 규칙의 핵심. 어떤 프레임워크에도 의존하지 않는다.

1. **Aggregate Root** — `account.py`의 `Account`. 일반 클래스(`__init__` + 메서드)로 구현되어 있다 — 상태 변경(`deposit`, `withdraw`, `suspend`, `reactivate`, `close`)은 모두 Aggregate 메서드를 통해서만 일어난다.
2. **Entity** — `transaction.py`의 `Transaction`. `@dataclass(frozen=True)`이지만 `transaction_id`라는 고유 식별자로 동등성이 결정되므로 Entity다.
3. **Value Object** — `money.py`의 `Money`. `@dataclass(frozen=True)`, 식별자 없음, 속성 조합으로 동등성 판단.
4. **Domain Event** — `events.py`의 `AccountCreated`, `MoneyDeposited` 등. 모두 `@dataclass(frozen=True)`, 과거형 이름.
5. **Repository 인터페이스** — `repository.py`의 `AccountRepository(ABC)`. 구현은 `infrastructure/`.

```python
# domain/repository.py — Repository 인터페이스 (ABC)
from abc import ABC, abstractmethod

from .account import Account
from .transaction import Transaction


class AccountRepository(ABC):
    @abstractmethod
    async def find_accounts(
        self, page: int, take: int,
        account_id: str | None = None, owner_id: str | None = None, status: list[str] | None = None,
    ) -> tuple[list[Account], int]: ...

    @abstractmethod
    async def save(self, account: Account) -> None: ...
```

→ Aggregate/Entity/Value Object 설계 상세는 [tactical-ddd.md](tactical-ddd.md) 참조.

---

## Application 레이어 — `application/`

유스케이스 **조율자**. 비즈니스 로직을 직접 수행하지 않고 Aggregate에 위임한다. `command/`(쓰기)와 `query/`(읽기)로 분리되어 있다.

```python
# application/command/deposit_handler.py
class DepositHandler:
    def __init__(self, repo: AccountRepository, outbox_relay: OutboxRelay) -> None:
        self._repo = repo
        self._outbox_relay = outbox_relay

    async def execute(self, cmd: DepositCommand) -> Transaction:
        accounts, _ = await self._repo.find_accounts(page=0, take=1, account_id=cmd.account_id, owner_id=cmd.requester_id)
        account = accounts[0] if accounts else None
        if account is None:
            raise AccountNotFoundError(cmd.account_id)
        transaction = account.deposit(cmd.amount)   # 비즈니스 로직은 Aggregate에 위임
        await self._repo.save(account)               # Aggregate 저장 + Outbox 적재, 한 트랜잭션
        await self._outbox_relay.process_pending()    # 커밋 직후 동기 드레인 — domain-events.md 참고
        return transaction
```

Handler 생성자는 구체 클래스가 아니라 ABC(`AccountRepository`, `OutboxRelay`)를 타입으로 받는다. 이 덕분에 [testing.md](testing.md)에서 다루는 Application 단위 테스트가 실제 DB/SES 없이 mock만으로 가능하다. `NotificationService`는 Command Handler가 아니라, Outbox가 드레인한 이벤트를 처리하는 `application/event/<event>_event_handler.py`가 의존한다([domain-events.md](domain-events.md) 참고).

→ Command/Query Handler 상세는 [cqrs-pattern.md](cqrs-pattern.md) 참조.

### Technical Service 인터페이스 — `application/service/`

기술적 구현이 핵심인 관심사(이메일 발송, 파일 스토리지, Secrets Manager 등)는 Application에 ABC를 두고 Infrastructure에서 구현한다. 이 저장소의 예:

```python
# application/service/notification_service.py — 인터페이스
class NotificationService(ABC):
    @abstractmethod
    async def notify(self, event: AccountDomainEvent, outbox_event_id: str) -> None: ...
```

```python
# infrastructure/notification/notification_service.py — 구현체 (SES)
class SesNotificationService(NotificationService):
    async def notify(self, event: AccountDomainEvent, outbox_event_id: str) -> None:
        try:
            await self._send_and_record(event, outbox_event_id)  # outbox_event_id로 중복 발송 여부를 먼저 확인한다
        except Exception:
            logger.exception("알림 이메일 발송 실패: ...")
```

**이 분리의 이유:**
- `DepositHandler`가 `aioboto3`나 SES API에 직접 의존하지 않는다.
- 발송 채널이 SES → SNS/Slack 등으로 바뀌어도 구현체만 교체하면 된다.
- 테스트 시 `NotificationService`를 mock 객체로 대체해 실제 이메일 발송 없이 Handler 로직만 검증할 수 있다.

두 번째 기술 관심사(파일 스토리지, Secrets Manager)를 추가할 때도 같은 구조(`application/service/<concern>_service.py` ABC + `infrastructure/<concern>/<provider>_<concern>_service.py` 구현체)를 따른다 — [file-storage.md](file-storage.md), [secret-manager.md](secret-manager.md) 참조.

---

### Domain Service — 여러 Aggregate를 조율하는 순수 도메인 로직

Account/Card는 각자 단일 Aggregate BC라 "하나의 Aggregate로는 판단할 수 없는 규칙"을 보여줄 수 없었다.
`examples/src/payment/`(Payment BC)는 `Payment`/`Refund` 두 Aggregate를 갖는 첫 도메인이라, 이
패턴을 실제로 동작하는 코드로 확인할 수 있다.

**도메인 규칙**: "환불은 원 결제가 COMPLETED 상태여야 하고, 환불 금액이 결제 금액을 넘을 수 없다."
`Payment`는 자신에 대한 환불 시도(`Refund`)를 모르고(환불은 별도 Aggregate로만 존재), `Refund`는
원 결제의 금액·상태를 모른다(`payment_id`로 참조만 한다). 어느 한쪽 Aggregate의 메서드로 이
판단을 넣으려면 다른 쪽 Aggregate 전체를 파라미터로 받아야 해서 경계가 무너지므로, 두 Aggregate를
모두 로드한 Application 레이어가 위임하는 별도의 Domain Service에 위치한다:

```python
# domain/refund_eligibility_service.py — Domain Service. FastAPI의 Depends(다른 어떤
# DI 컨테이너)에도 등록하지 않는다 — 상태가 없는 순수 판단 로직이라 Application
# 레이어가 필요할 때 직접 인스턴스화해 쓴다.
@dataclass(frozen=True)
class RefundDecision:
    approved: bool
    reason: str | None = None


class RefundEligibilityService:
    def evaluate(self, payment: Payment, refund: Refund) -> RefundDecision:
        if payment.status != PaymentStatus.COMPLETED:
            return RefundDecision(approved=False, reason="완료된 결제에 대해서만 환불을 요청할 수 있습니다.")
        if refund.amount > payment.amount:
            return RefundDecision(approved=False, reason="환불 금액은 결제 금액을 초과할 수 없습니다.")
        return RefundDecision(approved=True)
```

```python
# application/command/request_refund_handler.py — 두 Repository를 로드해 위임
class RequestRefundHandler:
    def __init__(self, payment_repo: PaymentRepository, refund_repo: RefundRepository, outbox_relay: OutboxRelay) -> None:
        self._payment_repo = payment_repo
        self._refund_repo = refund_repo
        self._outbox_relay = outbox_relay
        self._refund_eligibility_service = RefundEligibilityService()

    async def execute(self, cmd: RequestRefundCommand) -> Refund:
        payments, _ = await self._payment_repo.find_payments(page=0, take=1, payment_id=cmd.payment_id, owner_id=cmd.requester_id)
        payment = payments[0] if payments else None
        if payment is None:
            raise PaymentNotFoundError(cmd.payment_id)

        refund = Refund.create(payment_id=payment.payment_id, amount=cmd.amount, reason=cmd.reason)

        decision = self._refund_eligibility_service.evaluate(payment, refund)
        if decision.approved:
            refund.approve(account_id=payment.account_id, owner_id=payment.owner_id)
        else:
            # 환불 거부는 유효한 도메인 판단 결과다 — 예외를 던지지 않고 REJECTED로
            # 저장한 Refund를 그대로 반환한다. Interface 레이어가 이를 201 + status
            # 필드로 응답한다(4xx 에러가 아니다).
            refund.reject(decision.reason or "환불 요청이 거부되었습니다.")

        await self._refund_repo.save(refund)
        await self._outbox_relay.process_pending()
        return refund
```

`RefundEligibilityService`는 순수 함수적 판단만 한다 — Repository를 직접 호출하지 않는다(그랬다면
Domain Service를 잘못 쓰는 패턴, root [domain-service.md](../../../../docs/architecture/domain-service.md)의
"Domain Service를 잘못 쓰는 패턴" 참고). 로드는 항상 Application 레이어(`RequestRefundHandler`)의 몫이다.

단위 테스트도 Application 레이어를 거치지 않고 `RefundEligibilityService`를 직접 인스턴스화해 판단
로직만 검증한다(`tests/unit/domain/test_refund_eligibility_service.py`).

전체 코드: `examples/src/payment/domain/refund_eligibility_service.py`, `payment.py`, `refund.py`,
`examples/src/payment/application/command/request_refund_handler.py`.

---

## Infrastructure 레이어 — `infrastructure/`

외부 시스템에 실제로 접근하는 유일한 레이어.

```python
# infrastructure/persistence/account_repository.py
class SqlAlchemyAccountRepository(AccountRepository):
    def __init__(self, session: AsyncSession) -> None:
        self._session = session

    async def find_accounts(self, page: int, take: int, account_id=None, owner_id=None, status=None):
        stmt = select(AccountModel).where(AccountModel.deleted_at.is_(None))
        if account_id:
            stmt = stmt.where(AccountModel.id == account_id)
        if owner_id:
            stmt = stmt.where(AccountModel.owner_id == owner_id)
        # ... (전체 건수 count_stmt, offset/limit 적용은 repository-pattern.md 참고)
        rows = (await self._session.execute(stmt.offset(page * take).limit(take))).scalars().all()
        return [self._to_domain(row) for row in rows], total
```

Domain/Application의 ABC 구현체(`SqlAlchemyAccountRepository`, `SesNotificationService`)가 모두 여기 있다. FastAPI에는 전용 DI 컨테이너가 없으므로, `interface/rest/account_router.py`의 `Depends` 팩토리 함수가 "바인딩 지점" 역할을 한다:

```python
# interface/rest/account_router.py
def _repo(session: AsyncSession = Depends(get_session)) -> SqlAlchemyAccountRepository:
    return SqlAlchemyAccountRepository(session)   # AccountRepository(ABC) ← SqlAlchemyAccountRepository(구현체)


def _notification_service(session: AsyncSession = Depends(get_session)) -> NotificationService:
    return SesNotificationService(session)        # NotificationService(ABC) ← SesNotificationService(구현체)
```

라우트 함수의 파라미터 타입은 ABC(`AccountRepository`, `OutboxRelay`)로 선언되지만, 실제로 주입되는 것은 팩토리가 반환하는 구현체다. `NotificationService`는 라우트가 직접 받지 않는다 — `_outbox_relay()` 팩토리 내부에서만 조립되어 이벤트 핸들러에 전달된다([domain-events.md](domain-events.md) 참고).

---

## Interface 레이어 — `interface/rest/`

외부 요청(HTTP)의 진입점.

1. 요청 수신 (`account_router.py`)
2. Handler 생성 및 `execute()` 호출
3. 에러는 `main.py`의 `@app.exception_handler`가 캐치하여 HTTP 응답으로 변환 (Router 자체는 캐치하지 않는다)

```python
# interface/rest/account_router.py — 실제 코드
@router.post("/{account_id}/deposit", status_code=201, response_model=TransactionResponse)
async def deposit(
    account_id: str,
    body: DepositRequest,
    current_user: CurrentUser = Depends(get_current_user),
    repo: SqlAlchemyAccountRepository = Depends(_repo),
    outbox_relay: OutboxRelay = Depends(_outbox_relay),
) -> TransactionResponse:
    transaction = await DepositHandler(repo, outbox_relay).execute(
        DepositCommand(account_id=account_id, requester_id=current_user.user_id, amount=body.amount)
    )
    return TransactionResponse(...)
```

`current_user`는 JWT 검증을 통과한 사용자 정보다([authentication.md](authentication.md) 참고) — 클라이언트가 임의로 넣을 수 있는 헤더 값이 아니다.

### Interface DTO는 얇은 변환만

`interface/rest/schemas.py`의 Pydantic 모델(`DepositRequest`, `TransactionResponse`)은 HTTP 요청/응답 형태만 정의하고, 검증 이상의 로직을 갖지 않는다. Application의 Command/Result를 그대로 감싸는 역할이다.

---

### 관련 문서

- [tactical-ddd.md](tactical-ddd.md) — Aggregate, Entity, Value Object, Domain Event 상세
- [repository-pattern.md](repository-pattern.md) — Repository 패턴 상세
- [cqrs-pattern.md](cqrs-pattern.md) — Command/Query Handler 상세
- [domain-events.md](domain-events.md) — Domain Event, Outbox 패턴
- [directory-structure.md](directory-structure.md) — 전체 디렉토리 트리
- root [domain-service.md](../../../../docs/architecture/domain-service.md) — Domain Service/Technical Service 패턴의 프레임워크 무관 원칙(이 문서 "Domain Service" 섹션의 `RefundEligibilityService`가 실제 코드 근거)

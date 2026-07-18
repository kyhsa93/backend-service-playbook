from abc import ABC, abstractmethod

from .account import Account
from .transaction import Transaction


class AccountQuery(ABC):
    """읽기 전용 인터페이스 — Query Handler 전용. `save()` 등 쓰기 메서드를 노출하지 않는다
    (cqrs-pattern.md 참고). `AccountRepository`(쓰기 모델)와 메서드 시그니처를 공유하지만
    별도 계약이다 — Query Handler는 반드시 이 타입으로만 의존해야 한다.
    """

    @abstractmethod
    async def find_accounts(
        self,
        page: int,
        take: int,
        account_id: str | None = None,
        owner_id: str | None = None,
        status: list[str] | None = None,
    ) -> tuple[list[Account], int]: ...

    @abstractmethod
    async def find_transactions(self, account_id: str, page: int, take: int) -> tuple[list[Transaction], int]: ...


class AccountRepository(AccountQuery, ABC):
    @abstractmethod
    async def save(self, account: Account) -> None: ...

    @abstractmethod
    async def has_transaction_with_reference(self, reference_id: str, type: str) -> bool:
        """Payment BC의 Integration Event 반응(WithdrawByPaymentHandler/DepositByPaymentHandler)이
        at-least-once 재수신에도 같은 거래를 두 번 만들지 않도록 확인하는 멱등성 체크다
        (Level 2 Ledger — domain-events.md 참고). Card의 상태 기반 멱등성(이미 정지된 카드는
        다시 정지해도 무해)과 달리 금액 이동은 반복 적용하면 결과가 달라지므로 별도의
        처리 여부 확인이 필요하다.

        type도 함께 확인해야 한다 — 결제완료(WITHDRAWAL)와 그 결제취소 보상 크레딧
        (DEPOSIT)은 같은 payment_id를 reference_id로 공유하는 서로 다른 거래이므로,
        reference_id만으로 확인하면 보상 크레딧이 "이미 처리됨"으로 잘못 판정되어 스킵된다.
        """
        ...

from dataclasses import dataclass

from ...domain.errors import (
    InsufficientBalanceError,
    LinkedAccountNotFoundError,
    LinkedCardNotFoundError,
    PaymentRequiresActiveAccountError,
    PaymentRequiresActiveCardError,
)
from ...domain.payment import Payment
from ...domain.payment_repository import PaymentRepository
from ..adapter.account_adapter import AccountAdapter
from ..adapter.card_adapter import CardAdapter


@dataclass
class CreatePaymentCommand:
    requester_id: str
    card_id: str
    amount: int


class CreatePaymentHandler:
    def __init__(
        self,
        repo: PaymentRepository,
        card_adapter: CardAdapter,
        account_adapter: AccountAdapter,
    ) -> None:
        self._repo = repo
        self._card_adapter = card_adapter
        self._account_adapter = account_adapter

    async def execute(self, cmd: CreatePaymentCommand) -> Payment:
        # 동기 Adapter(ACL)로 카드가 존재·활성 상태인지 확인 — 응답(결제 가부)에 필요하므로 동기 호출.
        card = await self._card_adapter.find_card(cmd.card_id, cmd.requester_id)
        if card is None:
            raise LinkedCardNotFoundError()
        if not card.active:
            raise PaymentRequiresActiveCardError()

        # 동기 Adapter(ACL)로 연결 계좌가 활성이고 잔액이 충분한지 확인(읽기 전용 판단).
        # 실제 차감은 여기서 하지 않는다 — payment.completed.v1을 Account BC가 구독해
        # 비동기로 수행한다(root의 "동기=조회, 비동기=상태변경" 원칙).
        account = await self._account_adapter.find_account(card.account_id, cmd.requester_id)
        if account is None:
            raise LinkedAccountNotFoundError()
        if not account.active:
            raise PaymentRequiresActiveAccountError()
        if account.balance_amount < cmd.amount:
            raise InsufficientBalanceError()

        payment = Payment.create(
            card_id=cmd.card_id, account_id=card.account_id, owner_id=cmd.requester_id, amount=cmd.amount
        )
        payment.complete()

        await self._repo.save_payment(payment)
        return payment

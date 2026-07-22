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
        # Confirms the card exists and is active via the synchronous Adapter (ACL) — a synchronous call
        # since the response (whether payment is allowed) needs it.
        card = await self._card_adapter.find_card(cmd.card_id, cmd.requester_id)
        if card is None:
            raise LinkedCardNotFoundError()
        if not card.active:
            raise PaymentRequiresActiveCardError()

        # Confirms the linked account is active and has a sufficient balance via the
        # synchronous Adapter (ACL) (a read-only decision). The actual debit isn't done
        # here — the Account BC subscribes to payment.completed.v1 and performs it
        # asynchronously (the root's "sync = lookup, async = state change" principle).
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

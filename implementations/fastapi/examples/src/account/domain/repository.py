from abc import ABC, abstractmethod

from .account import Account
from .transaction import Transaction


class AccountQuery(ABC):
    """A read-only interface — for the Query Handler only. Never exposes a write method
    such as `save()` (see cqrs-pattern.md). Shares its method signatures with
    `AccountRepository` (the write model) but is a separate contract — a Query Handler must
    always depend only on this type.
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
    async def save_account(self, account: Account) -> None: ...

    @abstractmethod
    async def has_transaction_with_reference(self, reference_id: str, type: str) -> bool:
        """An idempotency check ensuring that the Payment BC's Integration Event reactions
        (WithdrawByPaymentHandler/DepositByPaymentHandler) don't create the same transaction
        twice even on an at-least-once redelivery (a Level 2 Ledger — see domain-events.md).
        Unlike Card's state-based idempotency (re-suspending an already-suspended card is
        harmless), moving an amount produces a different result if applied repeatedly, so a
        separate check for whether it was already processed is required.

        The type must also be checked — a payment completion (WITHDRAWAL) and its
        payment-cancellation compensating credit (DEPOSIT) are different transactions that
        share the same payment_id as their reference_id, so checking by reference_id alone
        would incorrectly judge the compensating credit as "already processed" and skip it.
        """
        ...

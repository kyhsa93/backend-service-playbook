from dataclasses import dataclass

from ...domain.repository import AccountRepository


@dataclass
class DepositByPaymentCommand:
    account_id: str
    amount: int
    # Payment BC's payment_id (payment-cancellation compensating credit) or refund_id
    # (refund-approval credit). Used as the key for the idempotency check (Level 2 Ledger).
    reference_id: str


class DepositByPaymentHandler:
    """The reaction use case for both the Payment BC's payment.cancelled.v1
    (payment-cancellation compensating credit) and refund.approved.v1 (refund-approval
    credit) Integration Events — since both events perform the same action, "reverse an
    amount that was already debited," and differ only in reference_id (a payment_id or a
    refund_id), a single command is reused for both.

    Idempotency uses a Level 2 Ledger (reference_id+type), for the same reason as
    WithdrawByPaymentHandler. The reason it never references Outbox-related objects
    directly is the same — OutboxPoller/OutboxConsumer handle that independently.
    """

    def __init__(self, repo: AccountRepository) -> None:
        self._repo = repo

    async def execute(self, cmd: DepositByPaymentCommand) -> None:
        already_processed = await self._repo.has_transaction_with_reference(cmd.reference_id, "DEPOSIT")
        if already_processed:
            return

        accounts, _ = await self._repo.find_accounts(page=0, take=1, account_id=cmd.account_id)
        account = accounts[0] if accounts else None
        if account is None:
            return

        account.deposit(cmd.amount, reference_id=cmd.reference_id)
        await self._repo.save_account(account)

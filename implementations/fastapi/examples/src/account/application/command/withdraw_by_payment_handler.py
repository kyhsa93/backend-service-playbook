from dataclasses import dataclass

from ...domain.repository import AccountRepository


@dataclass
class WithdrawByPaymentCommand:
    account_id: str
    amount: int
    # Payment BC's payment_id. Used as the key for the idempotency check (Level 2 Ledger).
    reference_id: str


class WithdrawByPaymentHandler:
    """The reaction use case for the Payment BC's payment.completed.v1 Integration Event —
    actually performs here the debit that was already decided by the synchronous Adapter at
    payment time.

    Idempotency: unlike WithdrawHandler (a user's direct withdrawal), this reaction silently
    ignores the case where a WITHDRAWAL transaction for the same reference_id (payment_id)
    already exists — unlike Card's state-based idempotency, moving an amount repeatedly
    keeps decreasing the balance, so "has it already been processed" must be checked
    (a Level 2 Ledger, see domain-events.md).

    This Handler never references any Outbox-related object at all — the MoneyWithdrawn
    Domain Event that account.withdraw() leaves is published to SQS by OutboxPoller on the
    next tick, and OutboxConsumer independently receives and processes it (see
    outbox_poller.py/outbox_consumer.py). Card's
    CancelCardsByAccountHandler/SuspendCardsByAccountHandler likewise don't depend on those
    objects, for the same reason.
    """

    def __init__(self, repo: AccountRepository) -> None:
        self._repo = repo

    async def execute(self, cmd: WithdrawByPaymentCommand) -> None:
        already_processed = await self._repo.has_transaction_with_reference(cmd.reference_id, "WITHDRAWAL")
        if already_processed:
            return

        accounts, _ = await self._repo.find_accounts(page=0, take=1, account_id=cmd.account_id)
        account = accounts[0] if accounts else None
        if account is None:
            return  # Silently ignored if there's no target account to react on (e.g. the account was already deleted).

        account.withdraw(cmd.amount, reference_id=cmd.reference_id)
        await self._repo.save_account(account)

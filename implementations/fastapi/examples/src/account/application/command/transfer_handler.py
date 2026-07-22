from dataclasses import dataclass

from ....common.generate_id import generate_id
from ...domain.errors import AccountNotFoundError
from ...domain.repository import AccountRepository
from ...domain.transaction import Transaction
from ...domain.transfer_eligibility_service import TransferEligibilityService


@dataclass
class TransferCommand:
    source_account_id: str
    target_account_id: str
    requester_id: str
    amount: int


@dataclass
class TransferResult:
    transfer_id: str
    source_transaction: Transaction
    target_transaction: Transaction


class TransferHandler:
    def __init__(self, repo: AccountRepository) -> None:
        self._repo = repo
        # TransferEligibilityService is a pure Domain Service with no framework dependency.
        # It is never registered in any DI container, and is instantiated directly (the
        # same reason as RefundEligibilityService).
        self._transfer_eligibility_service = TransferEligibilityService()

    async def execute(self, cmd: TransferCommand) -> TransferResult:
        accounts, _ = await self._repo.find_accounts(
            page=0, take=1, account_id=cmd.source_account_id, owner_id=cmd.requester_id
        )
        source = accounts[0] if accounts else None
        if source is None:
            raise AccountNotFoundError(cmd.source_account_id)

        # The target is looked up with no owner filter — since sending money to another
        # person's account is the point of this feature, only its existence+active status
        # need to be checked (ownership verification applies only to the source).
        accounts, _ = await self._repo.find_accounts(page=0, take=1, account_id=cmd.target_account_id)
        target = accounts[0] if accounts else None
        if target is None:
            raise AccountNotFoundError(cmd.target_account_id)

        decision = self._transfer_eligibility_service.evaluate(source, target, cmd.amount)
        if not decision.approved:
            assert decision.error is not None
            raise decision.error

        # transfer_id doesn't introduce a new persisted Aggregate dedicated to this transfer
        # — it's used only as the reference_id that correlates the two Transaction rows.
        # Since the (reference_id, type) combination is already unique, the source
        # (WITHDRAWAL)/target (DEPOSIT) rows sharing the same transfer_id doesn't collide.
        # It's used as the raw 32-character value with no suffix — since the reference_id
        # column is VARCHAR(36), adding a suffix could exceed that limit.
        transfer_id = generate_id()
        source_transaction = source.withdraw(cmd.amount, reference_id=transfer_id)
        target_transaction = target.deposit(cmd.amount, reference_id=transfer_id)

        # Since both saves share the same AsyncSession through the router's
        # Depends(get_session) caching (persistence.md), these two calls are already
        # committed within a single physical transaction — no separate transaction manager
        # is needed.
        await self._repo.save_account(source)
        await self._repo.save_account(target)

        return TransferResult(
            transfer_id=transfer_id,
            source_transaction=source_transaction,
            target_transaction=target_transaction,
        )

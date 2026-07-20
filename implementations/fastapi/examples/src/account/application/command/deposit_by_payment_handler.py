from dataclasses import dataclass

from ...domain.repository import AccountRepository


@dataclass
class DepositByPaymentCommand:
    account_id: str
    amount: int
    # Payment BC의 payment_id(결제취소 보상 크레딧) 또는 refund_id(환불 승인 크레딧).
    # 멱등성 판단(Level 2 Ledger)의 키로 쓰인다.
    reference_id: str


class DepositByPaymentHandler:
    """Payment BC의 payment.cancelled.v1(결제취소 보상 크레딧) 및 refund.approved.v1
    (환불 승인 크레딧) Integration Event 둘 다에 대한 반응 유스케이스다 — 두 이벤트는
    "이미 차감된 금액을 되돌린다"는 동일한 동작이고 reference_id(payment_id 또는
    refund_id)만 다르므로 커맨드를 하나로 재사용한다.

    멱등성은 WithdrawByPaymentHandler와 동일한 이유로 Level 2 Ledger(reference_id+type)를
    쓴다. Outbox 관련 객체를 직접 참조하지 않는 이유도 동일하다 — OutboxPoller/OutboxConsumer가
    독립적으로 처리한다.
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

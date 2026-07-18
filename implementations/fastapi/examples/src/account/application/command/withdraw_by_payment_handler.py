from dataclasses import dataclass

from ...domain.repository import AccountRepository


@dataclass
class WithdrawByPaymentCommand:
    account_id: str
    amount: int
    # Payment BC의 payment_id. 멱등성 판단(Level 2 Ledger)의 키로 쓰인다.
    reference_id: str


class WithdrawByPaymentHandler:
    """Payment BC의 payment.completed.v1 Integration Event에 대한 반응 유스케이스 —
    결제 시점에 이미 동기 Adapter로 판정된 차감을 여기서 실제로 수행한다.

    멱등성: WithdrawHandler(사용자 직접 출금)와 달리 이 반응은 같은 reference_id
    (payment_id)의 WITHDRAWAL 거래가 이미 있으면 조용히 무시한다 — Card의 상태 기반
    멱등성과 달리 금액 이동은 반복 적용하면 잔액이 계속 줄어들므로 "이미 처리했는지"를
    확인해야 한다(Level 2 Ledger, domain-events.md 참고).

    이 Handler는 Outbox 릴레이 객체를 직접 참조하지 않는다 — account.withdraw()가 남기는
    MoneyWithdrawn Domain Event는, 이 반응을 촉발한 최초 process_pending() 호출의 다음
    패스에서 함께 드레인된다(outbox_relay.py의 다중 패스 드레인 설계 참고). Card의
    CancelCardsByAccountHandler/SuspendCardsByAccountHandler도 동일한 이유로 그 객체를
    의존하지 않는다.
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
            return  # 반응할 대상 계좌가 없으면 조용히 무시한다(예: 계좌가 이미 삭제됨).

        account.withdraw(cmd.amount, reference_id=cmd.reference_id)
        await self._repo.save(account)

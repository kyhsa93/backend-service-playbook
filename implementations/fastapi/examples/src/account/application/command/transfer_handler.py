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
        # TransferEligibilityService는 프레임워크 의존이 없는 순수 Domain Service다. 어떤
        # DI 컨테이너에도 등록하지 않고 직접 생성해 쓴다(RefundEligibilityService와 동일한
        # 이유).
        self._transfer_eligibility_service = TransferEligibilityService()

    async def execute(self, cmd: TransferCommand) -> TransferResult:
        accounts, _ = await self._repo.find_accounts(
            page=0, take=1, account_id=cmd.source_account_id, owner_id=cmd.requester_id
        )
        source = accounts[0] if accounts else None
        if source is None:
            raise AccountNotFoundError(cmd.source_account_id)

        # target은 소유자 필터 없이 조회한다 — 타인 계좌로 송금하는 것이 이 기능의 목적이라,
        # 존재+활성 여부만 확인하면 된다(소유권 확인은 source에만 적용).
        accounts, _ = await self._repo.find_accounts(page=0, take=1, account_id=cmd.target_account_id)
        target = accounts[0] if accounts else None
        if target is None:
            raise AccountNotFoundError(cmd.target_account_id)

        decision = self._transfer_eligibility_service.evaluate(source, target, cmd.amount)
        if not decision.approved:
            assert decision.error is not None
            raise decision.error

        # transfer_id는 이 송금 전용의 새 영속 Aggregate를 두지 않고, 두 Transaction 행을
        # 상관관계 짓는 reference_id로만 쓴다 — (reference_id, type) 조합이 이미 유니크하므로
        # source(WITHDRAWAL)/target(DEPOSIT) 두 행이 같은 transfer_id를 공유해도 충돌하지
        # 않는다. 접미사 없이 32자리 원본 그대로 쓴다 — reference_id 컬럼이 VARCHAR(36)이므로
        # 접미사를 붙이면 그 한도를 넘길 수 있다.
        transfer_id = generate_id()
        source_transaction = source.withdraw(cmd.amount, reference_id=transfer_id)
        target_transaction = target.deposit(cmd.amount, reference_id=transfer_id)

        # 두 저장 모두 라우터의 Depends(get_session) 캐싱으로 같은 AsyncSession을 공유하므로
        # (persistence.md), 이 두 호출은 이미 하나의 물리 트랜잭션 안에서 커밋된다 — 별도의
        # 트랜잭션 매니저가 필요 없다.
        await self._repo.save_account(source)
        await self._repo.save_account(target)

        return TransferResult(
            transfer_id=transfer_id,
            source_transaction=source_transaction,
            target_transaction=target_transaction,
        )

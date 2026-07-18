from ...account.domain.account_status import AccountStatus
from ...account.domain.repository import AccountQuery
from ..application.adapter.account_adapter import AccountAdapter, AccountView


class AccountAdapterImpl(AccountAdapter):
    """AccountAdapter의 구현체(ACL). Account BC가 공개한 읽기 인터페이스(AccountQuery)를
    주입받아 호출하고, Account BC의 모델·상태를 Card BC가 쓰는 최소 형태(AccountView)로
    번역한다. Account의 Repository 구현체나 도메인 객체를 직접 참조하지 않는다.
    """

    def __init__(self, account_query: AccountQuery) -> None:
        self._account_query = account_query

    async def find_account(self, account_id: str, owner_id: str) -> AccountView | None:
        accounts, _ = await self._account_query.find_accounts(page=0, take=1, account_id=account_id, owner_id=owner_id)
        account = accounts[0] if accounts else None
        # 상류의 "계좌 없음"(None)을 그대로 Card 도메인의 None 신호로 전달한다 (오염 방지).
        if account is None:
            return None
        return AccountView(account_id=account.account_id, active=account.status == AccountStatus.ACTIVE)

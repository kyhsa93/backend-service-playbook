from ...account.domain.account_status import AccountStatus
from ...account.domain.repository import AccountQuery
from ..application.adapter.account_adapter import AccountAdapter, AccountView


class AccountAdapterImpl(AccountAdapter):
    """The implementation of AccountAdapter (ACL). It's injected with and calls the read
    interface (AccountQuery) the Account BC exposes, and translates Account BC's
    model/status into the minimal shape the Payment BC uses (AccountView). It never
    references Account's Repository implementation or domain objects directly.
    """

    def __init__(self, account_query: AccountQuery) -> None:
        self._account_query = account_query

    async def find_account(self, account_id: str, owner_id: str) -> AccountView | None:
        accounts, _ = await self._account_query.find_accounts(page=0, take=1, account_id=account_id, owner_id=owner_id)
        account = accounts[0] if accounts else None
        if account is None:
            return None
        return AccountView(
            account_id=account.account_id,
            active=account.status == AccountStatus.ACTIVE,
            balance_amount=account.balance.amount,
            currency=account.balance.currency,
        )

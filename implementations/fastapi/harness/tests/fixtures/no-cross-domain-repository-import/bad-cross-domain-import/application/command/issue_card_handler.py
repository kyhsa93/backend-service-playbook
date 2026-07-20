from ....account.domain.repository import AccountQuery


class IssueCardHandler:
    def __init__(self, account_query: AccountQuery) -> None:
        self._account_query = account_query

from ...domain.repository import AccountQuery


class GetAccountHandler:
    def __init__(self, repo: AccountQuery) -> None:
        self._repo = repo

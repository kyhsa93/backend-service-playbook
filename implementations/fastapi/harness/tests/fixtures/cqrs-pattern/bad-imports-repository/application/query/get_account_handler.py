from ...domain.repository import AccountRepository


class GetAccountHandler:
    def __init__(self, repo: AccountRepository) -> None:
        self._repo = repo

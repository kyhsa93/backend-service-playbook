from ...domain.repository import AccountRepository


class DepositHandler:
    def __init__(self, repo: AccountRepository) -> None:
        self._repo = repo

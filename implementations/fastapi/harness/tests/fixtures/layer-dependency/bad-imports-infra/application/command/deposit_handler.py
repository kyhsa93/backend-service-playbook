from ...infrastructure.persistence.account_repository import SqlAlchemyAccountRepository


class DepositHandler:
    def __init__(self, repo: SqlAlchemyAccountRepository) -> None:
        self._repo = repo

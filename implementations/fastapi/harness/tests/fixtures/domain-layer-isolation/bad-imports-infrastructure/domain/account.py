from ..infrastructure.persistence.account_repository import SqlAlchemyAccountRepository


class Account:
    def deposit(self, amount: int) -> None:
        SqlAlchemyAccountRepository

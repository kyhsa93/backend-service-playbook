class AccountError(Exception):
    pass


class AccountNotFoundError(AccountError):
    def __init__(self, account_id: str) -> None:
        super().__init__(f"account not found: {account_id}")
        self.account_id = account_id


def ensure_found(account, account_id: str) -> None:
    if account is None:
        raise AccountNotFoundError(account_id)

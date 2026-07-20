class Account:
    def deposit(self, amount: int) -> None:
        if amount <= 0:
            raise ValueError("amount must be positive")

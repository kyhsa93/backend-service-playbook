class DepositHandler:
    async def execute(self, cmd) -> None:
        account = await self._repo.find_account(cmd.account_id)
        if account is None:
            raise ValueError("account not found")

class DepositHandler:
    def __init__(self, repo, outbox_poller) -> None:
        self._repo = repo
        self._outbox_poller = outbox_poller

    async def execute(self, cmd):
        account = await self._repo.find_by_id(cmd.account_id)
        await self._repo.save(account)
        # Even without using the symbol name as-is (the variable name is lowercase), calling a drain method itself is forbidden.
        await self._outbox_poller.run_forever()
        return account

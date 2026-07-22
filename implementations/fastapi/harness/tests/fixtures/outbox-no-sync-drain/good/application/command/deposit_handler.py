class DepositHandler:
    def __init__(self, repo) -> None:
        self._repo = repo

    async def execute(self, cmd):
        account = await self._repo.find_by_id(cmd.account_id)
        await self._repo.save(account)
        # Returns immediately after saving — publishing/receiving Outbox → SQS is the
        # sole responsibility of the independently, periodically running OutboxPoller/OutboxConsumer.
        return account

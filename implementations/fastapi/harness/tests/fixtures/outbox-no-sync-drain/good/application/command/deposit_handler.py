class DepositHandler:
    def __init__(self, repo) -> None:
        self._repo = repo

    async def execute(self, cmd):
        account = await self._repo.find_by_id(cmd.account_id)
        await self._repo.save(account)
        # 저장 후 곧바로 반환한다 — Outbox → SQS 발행/수신은 독립적으로 주기 실행되는
        # OutboxPoller/OutboxConsumer만의 책임이다.
        return account

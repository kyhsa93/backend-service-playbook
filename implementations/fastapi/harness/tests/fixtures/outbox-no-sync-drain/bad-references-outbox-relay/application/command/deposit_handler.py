from ....outbox.outbox_relay import OutboxRelay


class DepositHandler:
    def __init__(self, repo, outbox_relay: OutboxRelay) -> None:
        self._repo = repo
        self._outbox_relay = outbox_relay

    async def execute(self, cmd):
        account = await self._repo.find_by_id(cmd.account_id)
        await self._repo.save(account)
        await self._outbox_relay.process_pending()
        return account

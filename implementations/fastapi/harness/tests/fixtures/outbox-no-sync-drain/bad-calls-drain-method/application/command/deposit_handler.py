class DepositHandler:
    def __init__(self, repo, outbox_poller) -> None:
        self._repo = repo
        self._outbox_poller = outbox_poller

    async def execute(self, cmd):
        account = await self._repo.find_by_id(cmd.account_id)
        await self._repo.save(account)
        # 심볼 이름을 그대로 쓰지 않아도(변수명은 소문자) 드레인 메서드 호출 자체가 금지된다.
        await self._outbox_poller.run_forever()
        return account

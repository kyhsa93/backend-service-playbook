from dataclasses import dataclass

from ...domain.account import Account
from ...domain.repository import AccountRepository


@dataclass
class CreateAccountCommand:
    requester_id: str
    currency: str
    email: str


class CreateAccountHandler:
    def __init__(self, repo: AccountRepository) -> None:
        self._repo = repo

    async def execute(self, cmd: CreateAccountCommand) -> Account:
        account = Account.create(cmd.requester_id, cmd.currency, cmd.email)
        await self._repo.save_account(account)
        # 저장 후 곧바로 반환한다 — Outbox → SQS 발행/수신은 독립적으로 주기 실행되는
        # OutboxPoller/OutboxConsumer만의 책임이다(domain-events.md).
        return account

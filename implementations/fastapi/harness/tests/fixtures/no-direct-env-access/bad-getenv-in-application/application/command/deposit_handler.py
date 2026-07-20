import os


class DepositHandler:
    async def execute(self, cmd) -> None:
        url = os.environ["DATABASE_URL"]
        return url

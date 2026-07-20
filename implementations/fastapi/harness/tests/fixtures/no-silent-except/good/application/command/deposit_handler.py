import logging

logger = logging.getLogger(__name__)


class DepositHandler:
    async def execute(self, cmd) -> None:
        try:
            await self._risky()
        except Exception:
            logger.exception("deposit_failed")
            raise

    async def _risky(self) -> None:
        pass

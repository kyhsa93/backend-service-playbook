class SesNotificationService:
    async def notify(self, event) -> None:
        try:
            await self._send(event)
        except Exception:
            pass

    async def _send(self, event) -> None:
        pass

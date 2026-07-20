from apscheduler.schedulers.asyncio import AsyncIOScheduler

scheduler = AsyncIOScheduler()


@scheduler.scheduled_job("interval", seconds=5, id="cleanup-expired-accounts")
async def cleanup_expired_accounts() -> None:
    pass

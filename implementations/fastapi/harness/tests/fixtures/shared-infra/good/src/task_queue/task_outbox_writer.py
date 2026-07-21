class TaskOutboxWriter:
    async def enqueue(self, task_type: str, payload: dict) -> None:
        raise NotImplementedError

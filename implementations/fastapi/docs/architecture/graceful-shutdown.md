# Graceful Shutdown

> Framework-agnostic principles: [../../../../docs/architecture/graceful-shutdown.md](../../../../docs/architecture/graceful-shutdown.md)

## Current implementation

The shutdown block of `main.py`'s `lifespan` is filled in: upon receiving SIGTERM, `app_state["is_shutting_down"]` is flipped to `True` before any resource cleanup, so `/health/ready` immediately returns 503; then the APScheduler-based schedulers are stopped with `shutdown(wait=True)`, the Outbox/Task-queue background tasks are cancelled and awaited, the SQLAlchemy connection pool is cleaned up via `engine.dispose()`, and finally buffered tracing spans are flushed via `shutdown_tracing()`. The `/health/live` and `/health/ready` endpoints exist as shown below.

---

## `lifespan` — startup and shutdown

`lifespan` does not create the schema — the schema is managed via Alembic migrations (see persistence.md). The startup block fetches and injects the JWT secret from Secrets Manager in production (see secret-manager.md), starts the Outbox/Task-queue pollers and consumers as background tasks, and starts the APScheduler-based schedulers (see [domain-events.md](domain-events.md), [scheduling.md](scheduling.md)). `validate_env()` is invoked earlier than `lifespan`, at the module-import point at the very top of `main.py` (before the `FastAPI(...)` instance is even created) — see [config.md](config.md).

```python
# main.py — actual code (shortened: only one of the four schedulers is shown).
# validate_env() is called at module-import time, well before lifespan
from src.config.validator import validate_env

validate_env()  # on failure, the process exits right here — no code after this runs

from fastapi import FastAPI, Request  # noqa: E402

...
from src.database import SessionLocal, engine  # noqa: E402

app_state = {"is_shutting_down": False}


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncGenerator[None, None]:
    # --- startup (validate_env() has already run above, at module-import time) ---
    if os.getenv("APP_ENV") == "production":
        secret = await AwsSecretService().get_secret("app/jwt")
        set_jwt_secret(secret["secret"])

    background_tasks = [
        asyncio.create_task(OutboxPoller(SessionLocal).run_forever()),
        asyncio.create_task(OutboxConsumer(SessionLocal).run_forever()),
        asyncio.create_task(TaskOutboxPoller(SessionLocal).run_forever()),
        asyncio.create_task(TaskConsumer(SessionLocal).run_forever()),
    ]
    interest_scheduler = start_interest_scheduler(SessionLocal)
    logger.info("app_started")

    yield  # --- requests are handled in between ---

    # --- shutdown (SIGTERM received → uvicorn runs lifespan's shutdown block) ---
    app_state["is_shutting_down"] = True  # flips readiness to 503 immediately
    logger.info("shutdown_initiated")

    # scheduler.shutdown(wait=True) — waits for in-progress jobs to finish before shutting down
    interest_scheduler.shutdown(wait=True)

    # Cancels the background tasks and waits for them to fully finish — no dangling task is
    # left behind. An in-progress receive_message (up to WaitTimeSeconds) that
    # OutboxConsumer/TaskConsumer was waiting on is stopped immediately.
    for task in background_tasks:
        task.cancel()
    for task in background_tasks:
        try:
            await task
        except asyncio.CancelledError:
            pass

    try:
        await engine.dispose()  # clean up the DB connection pool
    except Exception:
        logger.exception("shutdown_cleanup_failed")  # don't re-raise even if cleanup fails

    shutdown_tracing()  # flushes any buffered spans (a no-op when no OTLP endpoint is set)

    logger.info("app_stopped")


# The schema is applied via `alembic upgrade head` in the deployment pipeline — this code
# does not call Base.metadata.create_all here (see docs/architecture/persistence.md).
app = FastAPI(title="Account Service", lifespan=lifespan)
```

**Why the order matters:** setting `is_shutting_down = True` before resource cleanup lets the readiness probe block new requests that come in during that window. The schedulers are stopped first (`shutdown(wait=True)` lets an in-progress Cron job finish), then the background tasks are cancelled and awaited, and only then is shared infrastructure torn down — the DB pool (`engine.dispose()`) last among the consumers' dependencies, followed by the tracing flush (`shutdown_tracing()`). uvicorn runs this whole block after in-flight requests have safely finished.

---

## Liveness / Readiness endpoints

```python
# main.py — actual code
@app.get("/health/live")
async def health_live() -> dict:
    return {"status": "ok"}  # always 200 — the process is alive even while shutting down


@app.get("/health/ready")
async def health_ready() -> JSONResponse:
    if app_state["is_shutting_down"]:
        return JSONResponse(status_code=503, content={"status": "shutting_down"})
    return JSONResponse(status_code=200, content={"status": "ready"})
```

| Probe | Purpose | Response while shutting down |
|--------|------|------------|
| `GET /health/live` | Confirms the process is alive | **200** (always) |
| `GET /health/ready` | Whether it can receive traffic | **503** (while shutting down) |

**Common mistake**: if liveness returns 503 while shutting down, the orchestrator force-restarts a container that is already in the middle of shutting down. Liveness must always return 200.

---

## uvicorn and SIGTERM

Upon receiving SIGTERM, `uvicorn` performs a graceful shutdown by default in this order: (1) stop accepting new connections (2) wait for in-flight requests to finish (3) run `lifespan`'s shutdown block. For this flow to actually work, the container's `CMD` must run `uvicorn` directly as PID 1.

```dockerfile
# Correct approach — uvicorn becomes PID 1 and receives SIGTERM immediately
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
```

→ See [container.md](container.md) for the full Dockerfile setup.

---

## terminationGracePeriodSeconds

Set the time the orchestrator waits between SIGTERM and SIGKILL comfortably above the service's p99 request-handling time. Since this repository's account deposit/withdrawal use case is a short transaction, the default 30 seconds is sufficient.

```yaml
# Kubernetes example
spec:
  terminationGracePeriodSeconds: 30
  containers:
    - livenessProbe:
        httpGet: { path: /health/live, port: 8000 }
    - readinessProbe:
        httpGet: { path: /health/ready, port: 8000 }
```

---

## Principles

- **`lifespan` handles both startup and shutdown**: before `yield` is startup, after it is shutdown.
- **The readiness transition happens before resource cleanup**: set the `is_shutting_down` flag before the cleanup code.
- **Liveness is always 200**: it returns 200 as long as the process is alive, even while shutting down.
- **Cleanup code swallows exceptions**: wrap it in try-except so an `engine.dispose()` failure doesn't block other cleanup steps.
- **CMD uses exec form**: uvicorn must directly receive SIGTERM as PID 1 for this entire flow to work.

---

### Related documents

- [container.md](container.md) — Dockerfile CMD, exposing health-check endpoints
- [config.md](config.md) — `validate_env()` fail-fast and startup order
- [observability.md](observability.md) — startup/shutdown logs

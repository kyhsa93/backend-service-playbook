# Container Image

> Framework-agnostic principles: [../../../../docs/architecture/container.md](../../../../docs/architecture/container.md)

## Current implementation — `implementations/fastapi/examples/Dockerfile`

`examples/Dockerfile` and `examples/.dockerignore` actually exist, and implement the multi-stage build below as-is.

---

## Multi-stage build

```dockerfile
# implementations/fastapi/examples/Dockerfile

# ---- Stage 1: Build ----
FROM python:3.12-slim AS build

WORKDIR /app

# Only this stage includes build tools for dependencies that need compilation (e.g. asyncpg).
RUN apt-get update && apt-get install -y --no-install-recommends gcc libpq-dev \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir --prefix=/install -r requirements.txt

# ---- Stage 2: Production ----
FROM python:3.12-slim

WORKDIR /app

# Copy only the packages installed in the build stage — gcc/libpq-dev are absent from the final image.
COPY --from=build /install /usr/local

COPY main.py ./
COPY src ./src

# Never run as root inside the container.
RUN useradd --create-home appuser
USER appuser

EXPOSE 8000

# curl/wget aren't installed (slim image) — health-check using the Python standard library, no extra package.
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://localhost:8000/health/live').status==200 else 1)"

# exec form — uvicorn becomes PID 1 and receives SIGTERM immediately.
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
```

**Purpose of each stage:**

| Stage | Contents | In final image |
|---------|------|---------------|
| Build | `gcc`, `libpq-dev` (for compiling asyncpg) + the full set of Python packages | ✗ |
| Production | Only the installed packages (`/usr/local`) + `main.py`, `src/` | ✓ |

Some of `asyncpg`'s and `aioboto3`'s sub-dependencies need to build C extensions, so `pip install` fails without `gcc`/`libpq-dev`. Keeping these tools only in the build stage minimizes the final image size and attack surface.

---

## .dockerignore

```
# implementations/fastapi/examples/.dockerignore
__pycache__/
*.pyc
.pytest_cache/
.venv/
.git/
.env*
docker-compose.yml
localstack/
tests/
```

`tests/` and `docker-compose.yml` are unnecessary at runtime. Including `.env*` would risk leaking local-development sensitive values into the image.

---

## CMD — run the process directly with exec form

```dockerfile
# correct approach — run uvicorn directly as PID 1
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]

# incorrect approach — a shell sits in between, delaying SIGTERM delivery
CMD uvicorn main:app --host 0.0.0.0 --port 8000
```

With exec form (`["uvicorn", ...]`), the `uvicorn` process becomes PID 1 with no shell in between, so it receives SIGTERM immediately. With shell form (`CMD uvicorn ...`), `/bin/sh -c` becomes PID 1, and SIGTERM can stall in or be delayed by the shell.

→ See [graceful-shutdown.md](graceful-shutdown.md) for the flow where FastAPI's `lifespan` shutdown block runs after `uvicorn` receives SIGTERM.

---

## Never bake environment variables into the image

```dockerfile
# forbidden — hardcoding sensitive values into the image
ENV DATABASE_URL=postgresql+asyncpg://prod-user:realpassword@prod-db/account

# forbidden — copying a .env file into the image
COPY .env .env
```

Inject them at run time via `docker-compose.yml` or an orchestrator (an ECS Task Definition, a Kubernetes Secret, etc.). The pattern of `DatabaseConfig` (`src/config/database_config.py`, `pydantic_settings.BaseSettings`, `DATABASE_` environment-variable prefix) reading values from environment variables is correct in itself (see [config.md](config.md)) — but the values themselves must come from outside the image.

```bash
docker run --env-file .env.docker myapp
docker run -e DATABASE_URL=postgresql+asyncpg://... myapp
```

---

## Health-check endpoints

`main.py` provides liveness/readiness endpoints so an orchestrator can check instance health.

```
GET /health/live   → 200: confirms the process is alive
GET /health/ready  → 200: can receive traffic / 503: shutting down or not yet initialized
```

→ See [graceful-shutdown.md](graceful-shutdown.md) for implementation details and how it ties into `lifespan`.

---

## HEALTHCHECK — the container's own health status

```dockerfile
# curl/wget aren't installed (slim image) — health-check using the Python standard library, no extra package.
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://localhost:8000/health/live').status==200 else 1)"
```

The `python:3.12-slim` runtime image doesn't have `curl`/`wget` installed by default. Instead of adding one via `apt-get install curl`, this calls `/health/live` using only the Python interpreter (already present in the image) and the standard-library `urllib.request` — keeping the final image size and attack surface unchanged, with no extra package install or apt layer.

`docker inspect --format='{{.State.Health.Status}}' <container>` can be used to check the status (`starting`/`healthy`/`unhealthy`).

---

## Principles

- **A multi-stage build is required**: build-only tools such as `gcc`, `libpq-dev` must never be in the production image.
- **Run as a non-root user**: `USER appuser`.
- **Keep a `.dockerignore`**: `tests/`, `.env*`, `.git`, `docker-compose.yml` must always be excluded.
- **CMD uses exec form**: `["uvicorn", ...]` — for immediate SIGTERM receipt.
- **Environment variables are injected from outside the image**: the image itself must be identical regardless of environment.
- **A health-check endpoint is required**: `/health/live`, `/health/ready`.
- **HEALTHCHECK uses the standard library**: minimize image size/attack surface with `python -c "import urllib.request..."`, with no `curl`/`wget` install.

Whether there's a multi-stage build (2+ `FROM`s), a `HEALTHCHECK`, a `USER` (non-root execution), and a `.dockerignore` are all checked directly by the harness's `dockerfile-conventions` rule, which reads `Dockerfile`/`.dockerignore` itself.

---

### Related documents

- [graceful-shutdown.md](graceful-shutdown.md) — SIGTERM handling, health-check state management in `lifespan`
- [config.md](config.md) — environment variable/configuration management
- [local-dev.md](local-dev.md) — how to integrate this as the app service in `docker-compose.yml`

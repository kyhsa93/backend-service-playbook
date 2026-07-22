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

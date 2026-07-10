# ---- Stage 1: Build ----
FROM python:3.12-slim AS build

WORKDIR /app

# 컴파일이 필요한 의존성(asyncpg 등)의 빌드 도구만 이 스테이지에 포함한다.
RUN apt-get update && apt-get install -y --no-install-recommends gcc libpq-dev \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir --prefix=/install -r requirements.txt

# ---- Stage 2: Production ----
FROM python:3.12-slim

WORKDIR /app

# 빌드 스테이지에서 설치된 패키지만 복사 — gcc/libpq-dev는 최종 이미지에 없다.
COPY --from=build /install /usr/local

COPY main.py ./
COPY src ./src

# 컨테이너 안에서 루트로 실행하지 않는다.
RUN useradd --create-home appuser
USER appuser

EXPOSE 8000

# exec form — uvicorn이 PID 1이 되어 SIGTERM을 즉시 수신한다.
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]

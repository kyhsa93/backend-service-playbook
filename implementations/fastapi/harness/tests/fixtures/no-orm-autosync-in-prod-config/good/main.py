from contextlib import asynccontextmanager

from fastapi import FastAPI


# 스키마는 배포 파이프라인에서 `alembic upgrade head`로 적용한다 — 여기서
# Base.metadata.create_all을 호출하지 않는다.
@asynccontextmanager
async def lifespan(app: FastAPI):
    yield


app = FastAPI(lifespan=lifespan)

from contextlib import asynccontextmanager

from fastapi import FastAPI


# The schema is applied via `alembic upgrade head` in the deployment pipeline — this code
# does not call Base.metadata.create_all here.
@asynccontextmanager
async def lifespan(app: FastAPI):
    yield


app = FastAPI(lifespan=lifespan)

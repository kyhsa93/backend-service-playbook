# File Storage — the Presigned URL Pattern

> Framework-agnostic principles: [../../../../docs/architecture/file-storage.md](../../../../docs/architecture/file-storage.md)

`examples/` currently has no file upload/download feature (the Account domain doesn't handle files). This document newly defines how to implement the root principles with FastAPI/`aioboto3` — it reuses as-is the `aioboto3` client pattern already used by `notification_service.py`.

---

## Flow

```
[Upload]
1. Client → server: POST /accounts/{account_id}/statements (filename, extension)
2. Server: generates a file key → issues a Presigned Upload URL → saves metadata to the DB
3. Server → client: { file_key, extension, upload_url }
4. Client → S3: PUT upload_url (uploads the file binary directly)

[Download]
1. Client → server: GET /accounts/{account_id}/statements/{file_key}
2. Server: looks up metadata from the DB → issues a Presigned Download URL
3. Server → client: { download_url }
4. Client → S3: GET download_url
```

The server never receives the file binary into its own memory — so a large attachment never occupies a FastAPI worker's memory/network.

---

## StorageService — abstracted as a Technical Service

Applies the Technical Service pattern covered in [layer-architecture.md](layer-architecture.md) (the same structure as `application/service/notification_service.py`) as-is.

```python
# application/service/storage_service.py — interface
from abc import ABC, abstractmethod


class StorageService(ABC):
    @abstractmethod
    async def generate_upload_url(self, key: str, expires_in: int = 3600) -> str: ...

    @abstractmethod
    async def generate_download_url(self, key: str, expires_in: int = 3600) -> str: ...
```

```python
# infrastructure/storage/s3_storage_service.py — implementation
import os

import aioboto3


class S3StorageService(StorageService):
    def __init__(self, bucket: str) -> None:
        self._bucket = bucket
        self._boto_session = aioboto3.Session()

    async def generate_upload_url(self, key: str, expires_in: int = 3600) -> str:
        async with self._boto_session.client(
            "s3",
            region_name=os.getenv("AWS_REGION", "us-east-1"),
            endpoint_url=os.getenv("AWS_ENDPOINT_URL") or None,   # local uses LocalStack
            aws_access_key_id=os.getenv("AWS_ACCESS_KEY_ID", "test"),
            aws_secret_access_key=os.getenv("AWS_SECRET_ACCESS_KEY", "test"),
        ) as s3:
            return await s3.generate_presigned_url(
                "put_object", Params={"Bucket": self._bucket, "Key": key}, ExpiresIn=expires_in
            )

    async def generate_download_url(self, key: str, expires_in: int = 3600) -> str:
        async with self._boto_session.client(
            "s3",
            region_name=os.getenv("AWS_REGION", "us-east-1"),
            endpoint_url=os.getenv("AWS_ENDPOINT_URL") or None,
            aws_access_key_id=os.getenv("AWS_ACCESS_KEY_ID", "test"),
            aws_secret_access_key=os.getenv("AWS_SECRET_ACCESS_KEY", "test"),
        ) as s3:
            return await s3.generate_presigned_url(
                "get_object", Params={"Bucket": self._bucket, "Key": key}, ExpiresIn=expires_in
            )
```

Since `generate_presigned_url` only computes the signed URL string locally with no actual network call, the `async with` block finishes quickly — unlike `notification_service.py`'s `send_email` call, it doesn't wait on an actual API call.

---

## Only metadata is stored in the DB

The Entity that owns the file (e.g. `AccountStatement`) only has `file_key` and `extension` as columns.

```python
# infrastructure/persistence/statement_model.py — concept
class StatementModel(Base):
    __tablename__ = "account_statements"

    id: Mapped[str] = mapped_column(primary_key=True)     # file_key, following the aggregate-id.md rule
    account_id: Mapped[str]
    extension: Mapped[str]
    created_at: Mapped[datetime] = mapped_column(default=datetime.utcnow)
```

- **file_key**: generated with the same rule as [aggregate-id.md](aggregate-id.md) (32-character hex, hyphens removed) — used as-is as the object key in storage.
- **extension**: used to restore the original filename on download (`pdf`, `png`, etc.).

## Usage in an Application Handler

```python
# application/command/create_statement_handler.py — concept
from ..service.storage_service import StorageService


class CreateStatementHandler:
    def __init__(self, repo: StatementRepository, storage_service: StorageService) -> None:
        self._repo = repo
        self._storage_service = storage_service

    async def execute(self, cmd: CreateStatementCommand) -> CreateStatementResult:
        file_key = generate_id()
        key = f"{cmd.account_id}/{file_key}.{cmd.extension}"
        upload_url = await self._storage_service.generate_upload_url(key)

        await self._repo.save(Statement(file_key=file_key, account_id=cmd.account_id, extension=cmd.extension))

        return CreateStatementResult(file_key=file_key, extension=cmd.extension, upload_url=upload_url)
```

---

## Local development — LocalStack

`examples/docker-compose.yml` currently only enables `SERVICES: ses`. Once file storage is added, register `s3` alongside it.

```yaml
# docker-compose.yml — example edit
localstack:
  image: localstack/localstack:3.0
  environment:
    SERVICES: ses,s3
```

```bash
# localstack/init-s3.sh (proposed) — place alongside init-ses.sh in the same directory
#!/bin/sh
set -e
awslocal s3 mb s3://account-statements
```

LocalStack's S3 emulator may require `forcePathStyle` (path style rather than virtual-hosted style) — add `config=Config(s3={"addressing_style": "path"})` when constructing the `aioboto3` client.

→ See [local-dev.md](local-dev.md) for the full local-development setup, including health checks and `.env.development` configuration.

---

## Principles

- **The server never handles the file binary**: both upload and download go through a Presigned URL, communicating directly client↔S3.
- **Only metadata is stored in the DB**: `file_key`, `extension`, the ID of the owning Entity.
- **The implementation is abstracted via the `StorageService` ABC**: the same Technical Service structure as `notification_service.py`.
- **`file_key` follows the aggregate-id.md rule**: 32-character hex, hyphens removed.

### Related documents

- [layer-architecture.md](layer-architecture.md) — the Technical Service pattern
- [aggregate-id.md](aggregate-id.md) — the ID generation rule
- [local-dev.md](local-dev.md) — the LocalStack-based local development environment
- [persistence.md](persistence.md) — the common Entity columns rule

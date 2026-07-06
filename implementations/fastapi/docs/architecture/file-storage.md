# 파일 스토리지 — Presigned URL 패턴

> 프레임워크 무관 원칙: [../../../../docs/architecture/file-storage.md](../../../../docs/architecture/file-storage.md)

현재 `examples/`에는 파일 업로드/다운로드 기능이 없다 (Account 도메인은 파일을 다루지 않는다). 이 문서는 root 원칙을 FastAPI/`aioboto3`로 구현하는 방법을 새로 정의한다 — `notification_service.py`가 이미 사용 중인 `aioboto3` 클라이언트 패턴을 그대로 재사용한다.

---

## 흐름

```
[업로드]
1. 클라이언트 → 서버: POST /accounts/{account_id}/statements (파일명, 확장자)
2. 서버: 파일 키 생성 → Presigned Upload URL 발급 → DB에 메타데이터 저장
3. 서버 → 클라이언트: { file_key, extension, upload_url }
4. 클라이언트 → S3: PUT upload_url (파일 바이너리 직접 업로드)

[다운로드]
1. 클라이언트 → 서버: GET /accounts/{account_id}/statements/{file_key}
2. 서버: DB에서 메타데이터 조회 → Presigned Download URL 발급
3. 서버 → 클라이언트: { download_url }
4. 클라이언트 → S3: GET download_url
```

서버는 파일 바이너리를 자신의 메모리로 받지 않는다 — 대용량 첨부파일이 FastAPI 워커의 메모리/네트워크를 점유하지 않도록 한다.

---

## StorageService — Technical Service로 추상화

[layer-architecture.md](layer-architecture.md)에서 다루는 Technical Service 패턴(`application/service/notification_service.py`와 동일한 구조)을 그대로 적용한다.

```python
# application/service/storage_service.py — 인터페이스
from abc import ABC, abstractmethod


class StorageService(ABC):
    @abstractmethod
    async def generate_upload_url(self, key: str, expires_in: int = 3600) -> str: ...

    @abstractmethod
    async def generate_download_url(self, key: str, expires_in: int = 3600) -> str: ...
```

```python
# infrastructure/storage/s3_storage_service.py — 구현체
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
            endpoint_url=os.getenv("AWS_ENDPOINT_URL") or None,   # 로컬은 LocalStack
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

`generate_presigned_url`은 실제 네트워크 호출 없이 서명된 URL 문자열만 로컬에서 계산하므로 `async with` 블록이 짧게 끝난다 — `notification_service.py`의 `send_email` 호출과 달리 실제 API 호출을 기다리지 않는다.

---

## 메타데이터만 DB에 저장

파일을 소유하는 Entity(예: `AccountStatement`)는 `file_key`와 `extension`만 컬럼으로 가진다.

```python
# infrastructure/persistence/statement_model.py — 개념
class StatementModel(Base):
    __tablename__ = "account_statements"

    id: Mapped[str] = mapped_column(primary_key=True)     # file_key, aggregate-id.md 규칙 준수
    account_id: Mapped[str]
    extension: Mapped[str]
    created_at: Mapped[datetime] = mapped_column(default=datetime.utcnow)
```

- **file_key**: [aggregate-id.md](aggregate-id.md)와 동일한 규칙(하이픈 제거 32자리 hex)으로 생성한다 — 스토리지 내 객체 키로 그대로 쓰인다.
- **extension**: 다운로드 시 원본 파일명 복원에 사용한다 (`pdf`, `png` 등).

## Application Handler에서 사용

```python
# application/command/create_statement_handler.py — 개념
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

## 로컬 개발 — LocalStack

`examples/docker-compose.yml`은 현재 `SERVICES: ses`만 활성화하고 있다. 파일 스토리지를 추가하면 `s3`를 함께 등록한다.

```yaml
# docker-compose.yml — 수정 예시
localstack:
  image: localstack/localstack:3.0
  environment:
    SERVICES: ses,s3
```

```bash
# localstack/init-s3.sh (신설 제안) — init-ses.sh와 같은 디렉토리에 나란히 배치
#!/bin/sh
set -e
awslocal s3 mb s3://account-statements
```

LocalStack의 S3 에뮬레이터는 `forcePathStyle`(가상 호스팅 스타일이 아닌 path 스타일)이 필요할 수 있다 — `aioboto3` 클라이언트 생성 시 `config=Config(s3={"addressing_style": "path"})`를 추가한다.

→ Health check, `.env.development` 구성 등 전체 로컬 개발 설정은 [local-dev.md](local-dev.md) 참조.

---

## 원칙

- **서버는 파일 바이너리를 처리하지 않는다**: 업로드/다운로드 모두 Presigned URL을 통해 클라이언트↔S3 직접 통신.
- **DB에는 메타데이터만 저장한다**: `file_key`, `extension`, 소유 Entity의 ID.
- **StorageService ABC로 구현을 추상화한다**: `notification_service.py`와 동일한 Technical Service 구조.
- **file_key는 aggregate-id.md 규칙을 따른다**: 하이픈 제거 32자리 hex.

### 관련 문서

- [layer-architecture.md](layer-architecture.md) — Technical Service 패턴
- [aggregate-id.md](aggregate-id.md) — ID 생성 규칙
- [local-dev.md](local-dev.md) — LocalStack 기반 로컬 개발 환경
- [persistence.md](persistence.md) — Entity 공통 컬럼 규칙

# 파일 스토리지 — Presigned URL 패턴

파일을 서버에서 직접 업로드/다운로드하지 않는다. **Presigned URL을 발급하여 클라이언트가 스토리지(S3, GCS 등)와 직접 통신**하도록 한다.

**이유:**
- 서버의 네트워크/메모리 부하를 방지한다 (대용량 파일이 서버를 경유하지 않음).
- 서버는 URL 발급과 메타데이터 관리만 담당한다.

---

## 흐름

```
[업로드]
1. 클라이언트 → 서버: POST /orders/:orderId/attachments (파일명, 확장자 전달)
2. 서버: 파일 키 생성 → Presigned Upload URL 발급 → DB에 메타데이터 저장
3. 서버 → 클라이언트: { fileKey, extension, uploadUrl }
4. 클라이언트 → 스토리지: PUT uploadUrl (파일 바이너리 직접 업로드)

[다운로드]
1. 클라이언트 → 서버: GET /orders/:orderId/attachments/:fileKey
2. 서버: DB에서 파일 메타데이터 조회 → Presigned Download URL 발급
3. 서버 → 클라이언트: { downloadUrl }
4. 클라이언트 → 스토리지: GET downloadUrl (파일 직접 다운로드)
```

## StorageService — Technical Service로 추상화

[domain-service.md](domain-service.md)의 **Technical Service** 패턴을 그대로 적용한다: Application 레이어에 인터페이스, Infrastructure 레이어에 구현체.

```typescript
// application/service/storage-service — 인터페이스
abstract class StorageService {
  abstract generateUploadUrl(key: string): Promise<string>
  abstract generateDownloadUrl(key: string): Promise<string>
}
```

```typescript
// infrastructure/storage-service-impl — S3 구현 예시
class StorageServiceImpl implements StorageService {
  private readonly client = createS3Client({
    endpoint: process.env.AWS_ENDPOINT_URL,  // 로컬은 LocalStack (path-style 필요)
    forcePathStyle: Boolean(process.env.AWS_ENDPOINT_URL)
  })
  private readonly bucket = process.env.S3_BUCKET

  async generateUploadUrl(key: string): Promise<string> {
    return this.client.getSignedPutUrl(this.bucket, key, { expiresIn: 3600 })
  }

  async generateDownloadUrl(key: string): Promise<string> {
    return this.client.getSignedGetUrl(this.bucket, key, { expiresIn: 3600 })
  }
}
```

## Entity에는 메타데이터만 저장

파일을 소유하는 Entity는 **파일 키(fileKey)와 확장자(extension)** 를 컬럼으로 가진다. 파일 자체는 스토리지에 저장하고, DB에는 메타데이터만 기록한다.

```typescript
// infrastructure/order-attachment-entity — 개념
class OrderAttachmentEntity extends BaseEntity {
  fileKey: string     // 스토리지 내 파일 식별자 (UUID 등, 하이픈 제거)
  orderId: string
  extension: string   // 다운로드 시 원본 파일명 복원에 사용
}
```

- **fileKey**: 스토리지 내의 파일 식별자. Presigned URL 발급 시 이 키를 사용한다.
- **extension**: 파일 확장자 (`pdf`, `png`, `xlsx` 등).
- 파일명, 크기 등 추가 메타데이터가 필요하면 컬럼을 추가한다.

## Application Service에서 사용

```typescript
async createAttachment(command: CreateAttachmentCommand): Promise<CreateAttachmentResult> {
  const fileKey = generateId()
  const uploadUrl = await this.storageService.generateUploadUrl(
    `${command.orderId}/${fileKey}.${command.extension}`
  )
  await this.attachmentRepository.saveAttachment({
    fileKey,
    orderId: command.orderId,
    extension: command.extension
  })
  return { fileKey, extension: command.extension, uploadUrl }
}

async getAttachmentUrl(param: { fileKey: string }): Promise<{ downloadUrl: string }> {
  const attachment = await this.attachmentRepository
    .findAttachments({ fileKey: param.fileKey, take: 1, page: 0 })
    .then((r) => r.attachments.pop())
  if (!attachment) throw new Error(ErrorMessage['파일을 찾을 수 없습니다.'])

  const downloadUrl = await this.storageService.generateDownloadUrl(
    `${attachment.orderId}/${attachment.fileKey}.${attachment.extension}`
  )
  return { downloadUrl }
}
```

## 로컬 개발 — LocalStack

```yaml
# docker-compose.yml — LocalStack SERVICES에 s3 추가
localstack:
  image: localstack/localstack
  environment:
    SERVICES: s3,sqs,secretsmanager
```

```bash
# localstack/init-aws.sh
awslocal s3 mb s3://app-files
```

자세한 로컬 개발 구성은 [local-dev.md](local-dev.md) 참고.

---

## 원칙

- **서버는 파일 바이너리를 처리하지 않는다**: 업로드/다운로드 모두 Presigned URL을 통해 클라이언트↔스토리지 직접 통신.
- **DB에는 메타데이터만 저장한다**: fileKey, extension, 소유 Entity의 ID.
- **StorageService 인터페이스로 스토리지 구현을 추상화한다**: S3, GCS, MinIO 등 구현체만 교체.

### 관련 문서

- [domain-service.md](domain-service.md) — Technical Service 패턴
- [local-dev.md](local-dev.md) — LocalStack 기반 로컬 개발 환경
- [persistence.md](persistence.md) — Entity 공통 컬럼 규칙

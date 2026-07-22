# File Storage — the Presigned URL Pattern

Never upload/download files directly through the server. **Issue a presigned URL and let the client talk to storage (S3, GCS, etc.) directly.**

**Why:**
- It avoids network/memory load on the server (large files never pass through the server).
- The server is only responsible for issuing URLs and managing metadata.

---

## Flow

```
[Upload]
1. Client → server: POST /orders/:orderId/attachments (passing the filename, extension)
2. Server: generates a file key → issues a presigned upload URL → saves metadata to the DB
3. Server → client: { fileKey, extension, uploadUrl }
4. Client → storage: PUT uploadUrl (uploads the file binary directly)

[Download]
1. Client → server: GET /orders/:orderId/attachments/:fileKey
2. Server: looks up the file metadata from the DB → issues a presigned download URL
3. Server → client: { downloadUrl }
4. Client → storage: GET downloadUrl (downloads the file directly)
```

## StorageService — abstracted as a Technical Service

Applies the exact same **Technical Service** pattern from [domain-service.md](domain-service.md): an interface in the Application layer, an implementation in the Infrastructure layer.

```typescript
// application/service/storage-service — the interface
abstract class StorageService {
  abstract generateUploadUrl(key: string): Promise<string>
  abstract generateDownloadUrl(key: string): Promise<string>
}
```

```typescript
// infrastructure/storage-service-impl — an S3 implementation example
class StorageServiceImpl implements StorageService {
  private readonly client = createS3Client({
    endpoint: process.env.AWS_ENDPOINT_URL,  // LocalStack locally (needs path-style)
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

## Store only metadata in the Entity

The Entity that owns a file holds a **file key (fileKey) and extension (extension)** as columns. The file itself lives in storage; the DB only records its metadata.

```typescript
// infrastructure/order-attachment-entity — conceptual
class OrderAttachmentEntity extends BaseEntity {
  fileKey: string     // the file's identifier in storage (e.g. a UUID, hyphens stripped)
  orderId: string
  extension: string   // used to restore the original filename on download
}
```

- **fileKey**: the file's identifier in storage. Used when issuing a presigned URL.
- **extension**: the file extension (`pdf`, `png`, `xlsx`, etc.).
- Add more columns if you need additional metadata like the filename or size.

## Using it in an Application Service

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
  if (!attachment) throw new Error(ErrorMessage['File not found.'])

  const downloadUrl = await this.storageService.generateDownloadUrl(
    `${attachment.orderId}/${attachment.fileKey}.${attachment.extension}`
  )
  return { downloadUrl }
}
```

## Local development — LocalStack

```yaml
# docker-compose.yml — add s3 to LocalStack's SERVICES
localstack:
  image: localstack/localstack
  environment:
    SERVICES: s3,sqs,secretsmanager
```

```bash
# localstack/init-aws.sh
awslocal s3 mb s3://app-files
```

See [local-dev.md](local-dev.md) for the detailed local dev setup.

---

## Principles

- **The server never handles file binaries**: both upload and download go through a presigned URL, with the client talking to storage directly.
- **Store only metadata in the DB**: fileKey, extension, and the owning Entity's ID.
- **Abstract the storage implementation behind a StorageService interface**: only the implementation (S3, GCS, MinIO, etc.) gets swapped.

### Related docs

- [domain-service.md](domain-service.md) — the Technical Service pattern
- [local-dev.md](local-dev.md) — a LocalStack-based local dev environment
- [persistence.md](persistence.md) — the common-column convention for an Entity

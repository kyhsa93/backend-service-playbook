# File Storage — the Presigned URL Pattern (NestJS)

> For the principles, see the root [file-storage.md](../../../../docs/architecture/file-storage.md) and the Technical Service pattern in [domain-service.md](../../../../docs/architecture/domain-service.md). This document focuses on NestJS implementation details.

Never upload/download files directly through the server. **Issue a Presigned URL and let the client communicate directly with storage (S3, etc.)**.

**Why:**
- Prevents network/memory load on the server (large files never pass through it).
- The server only handles issuing the URL and managing metadata.

### Flow

```
[Upload]
1. Client → server: POST /orders/:orderId/attachments (passes the file name, extension)
2. Server: generates a file key → issues a Presigned Upload URL → saves the metadata to the DB
3. Server → client: { fileKey, extension, uploadUrl }
4. Client → storage: PUT uploadUrl (uploads the file binary directly)

[Download]
1. Client → server: GET /orders/:orderId/attachments/:fileKey
2. Server: looks up the file metadata from the DB → issues a Presigned Download URL
3. Server → client: { downloadUrl }
4. Client → storage: GET downloadUrl (downloads the file directly)
```

### The StorageService Interface (application/service/)

Applies the Technical Service pattern (see [domain-service.md](../../../../docs/architecture/domain-service.md)) as-is: an interface in the Application layer, an implementation in the Infrastructure layer.

```typescript
// application/service/storage-service.ts — abstract class
export abstract class StorageService {
  abstract generateUploadUrl(key: string): Promise<string>
  abstract generateDownloadUrl(key: string): Promise<string>
}
```

### The StorageService Implementation (infrastructure/)

```typescript
// infrastructure/storage-service-impl.ts — an S3 implementation example
import { Injectable } from '@nestjs/common'
import { GetObjectCommand, PutObjectCommand, S3Client } from '@aws-sdk/client-s3'
import { getSignedUrl } from '@aws-sdk/s3-request-presigner'

import { StorageService } from '@/order/application/service/storage-service'

@Injectable()
export class StorageServiceImpl extends StorageService {
  private readonly s3 = new S3Client({
    ...(process.env.AWS_ENDPOINT_URL ? {
      endpoint: process.env.AWS_ENDPOINT_URL,
      forcePathStyle: true
    } : {})
  })
  private readonly bucket = process.env.S3_BUCKET!

  public async generateUploadUrl(key: string): Promise<string> {
    const command = new PutObjectCommand({ Bucket: this.bucket, Key: key })
    return getSignedUrl(this.s3, command, { expiresIn: 3600 })
  }

  public async generateDownloadUrl(key: string): Promise<string> {
    const command = new GetObjectCommand({ Bucket: this.bucket, Key: key })
    return getSignedUrl(this.s3, command, { expiresIn: 3600 })
  }
}
```

### Storing File Metadata on an Entity

The Entity that owns the file has the **file key (fileKey) and extension (extension)** as columns. The file itself is stored in storage; only metadata is recorded in the DB.

```typescript
// infrastructure/entity/order-attachment.entity.ts
import { Entity, PrimaryColumn, Column, ManyToOne, JoinColumn } from 'typeorm'

import { BaseEntity } from '@/database/base.entity'
import { OrderEntity } from '@/order/infrastructure/entity/order.entity'

@Entity('order_attachment')
export class OrderAttachmentEntity extends BaseEntity {
  @PrimaryColumn({ type: 'char', length: 32 })
  fileKey: string

  @Column({ type: 'char', length: 32 })
  orderId: string

  @Column({ type: 'varchar', length: 10 })
  extension: string

  @ManyToOne(() => OrderEntity)
  @JoinColumn({ name: 'orderId' })
  order: OrderEntity
}
```

- **fileKey**: the file's identifier within storage (a UUID v4 with hyphens removed). This key is used when issuing a Presigned URL.
- **extension**: the file extension (`pdf`, `png`, `xlsx`, etc.). Used to restore the original file name on download.
- Add more columns if you need additional metadata such as file name or size.

### Usage in the Application Service

```typescript
public async createAttachment(command: CreateAttachmentCommand): Promise<CreateAttachmentResult> {
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

public async getAttachmentUrl(param: { fileKey: string }): Promise<{ downloadUrl: string }> {
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

### Principles

- **The server never handles file binaries**: both upload and download go through the client↔storage direct communication via a Presigned URL.
- **The DB stores only metadata**: fileKey, extension, and the owning Entity's ID.
- **Abstract the storage implementation behind the StorageService interface**: only the implementation needs to be swapped — S3, GCS, MinIO, etc.

### Related Documents

- [domain-service.md](../../../../docs/architecture/domain-service.md) — the Technical Service pattern (root shared)
- [module-pattern.md](module-pattern.md) — Module DI registration

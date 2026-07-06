# 파일 스토리지 — Presigned URL 패턴 (NestJS)

> 원칙은 root [file-storage.md](../../../../docs/architecture/file-storage.md) 및 [domain-service.md](../../../../docs/architecture/domain-service.md)의 Technical Service 패턴을 참조한다. 이 문서는 NestJS 구현 상세에 집중한다.

파일을 서버에서 직접 업로드/다운로드하지 않는다. **Presigned URL을 발급하여 클라이언트가 스토리지(S3 등)와 직접 통신**하도록 한다.

**이유:**
- 서버의 네트워크/메모리 부하를 방지한다 (대용량 파일이 서버를 경유하지 않음).
- 서버는 URL 발급과 메타데이터 관리만 담당한다.

### 흐름

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

### StorageService 인터페이스 (application/service/)

Technical Service 패턴([domain-service.md](../../../../docs/architecture/domain-service.md) 참고)을 그대로 적용한다: Application 레이어에 인터페이스, Infrastructure 레이어에 구현체.

```typescript
// application/service/storage-service.ts — abstract class
export abstract class StorageService {
  abstract generateUploadUrl(key: string): Promise<string>
  abstract generateDownloadUrl(key: string): Promise<string>
}
```

### StorageService 구현체 (infrastructure/)

```typescript
// infrastructure/storage-service-impl.ts — S3 구현 예시
import { Injectable } from '@nestjs/common'
import { GetObjectCommand, PutObjectCommand, S3Client } from '@aws-sdk/client-s3'
import { getSignedUrl } from '@aws-sdk/s3-request-presigner'

import { StorageService } from '@/order/application/service/storage-service'

@Injectable()
export class StorageServiceImpl extends StorageService {
  private readonly s3 = new S3Client({
    ...(process.env.AWS_ENDPOINT ? {
      endpoint: process.env.AWS_ENDPOINT,
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

### Entity에 파일 메타데이터 저장

파일을 소유하는 Entity는 **파일 키(fileKey)와 확장자(extension)** 를 컬럼으로 가진다. 파일 자체는 스토리지에 저장하고, DB에는 메타데이터만 기록한다.

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

- **fileKey**: 스토리지 내의 파일 식별자 (UUID v4 하이픈 제거). Presigned URL 발급 시 이 키를 사용한다.
- **extension**: 파일 확장자 (`pdf`, `png`, `xlsx` 등). 다운로드 시 원본 파일명 복원에 사용한다.
- 파일명, 크기 등 추가 메타데이터가 필요하면 컬럼을 추가한다.

### Application Service에서 사용

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

### 원칙

- **서버는 파일 바이너리를 처리하지 않는다**: 업로드/다운로드 모두 Presigned URL을 통해 클라이언트↔스토리지 직접 통신.
- **DB에는 메타데이터만 저장한다**: fileKey, extension, 소유 Entity의 ID.
- **StorageService 인터페이스를 통해 스토리지 구현을 추상화한다**: S3, GCS, MinIO 등 구현체만 교체.

### 관련 문서

- [domain-service.md](../../../../docs/architecture/domain-service.md) — Technical Service 패턴 (루트 공용)
- [module-pattern.md](module-pattern.md) — Module DI 등록

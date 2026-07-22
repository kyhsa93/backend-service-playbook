# File Storage — Kotlin Spring Boot

> For the framework-agnostic principles, see [root file-storage.md](../../../../docs/architecture/file-storage.md).

## Current state

`examples/` has no file upload/download feature — the Account domain has no concept of an attachment at all. Below is how to add a `StorageService` by reusing the **Technical Service pattern** already present in this repository (the `notification/` module, see [directory-structure.md](directory-structure.md)) as-is. The `NotificationService`/`NotificationServiceImpl` pair is used as the template.

---

## StorageService — the interface (Application layer)

```kotlin
// storage/application/service/StorageService.kt
package com.example.accountservice.storage.application.service

interface StorageService {
    fun generateUploadUrl(key: String): String
    fun generateDownloadUrl(key: String): String
}
```

Just like `NotificationService`, this is an `interface` with no Spring dependency — the Application layer's caller (e.g. an attachment Command Service) doesn't know the AWS SDK exists at all.

---

## StorageServiceImpl — AWS SDK v2 for Kotlin (S3Presigner)

```kotlin
// storage/infrastructure/StorageServiceImpl.kt
package com.example.accountservice.storage.infrastructure

import com.example.accountservice.storage.application.service.StorageService
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import java.time.Duration

@Component
class StorageServiceImpl(
    private val presigner: S3Presigner,
    @Value("\${app.storage.bucket}") private val bucket: String,
) : StorageService {

    override fun generateUploadUrl(key: String): String {
        val objectRequest = PutObjectRequest.builder().bucket(bucket).key(key).build()
        val presignRequest = PutObjectPresignRequest.builder()
            .signatureDuration(Duration.ofHours(1))
            .putObjectRequest(objectRequest)
            .build()
        return presigner.presignPutObject(presignRequest).url().toString()
    }

    override fun generateDownloadUrl(key: String): String {
        val objectRequest = GetObjectRequest.builder().bucket(bucket).key(key).build()
        val presignRequest = GetObjectPresignRequest.builder()
            .signatureDuration(Duration.ofHours(1))
            .getObjectRequest(objectRequest)
            .build()
        return presigner.presignGetObject(presignRequest).url().toString()
    }
}
```

`S3Presigner` is registered as a Bean in a `@Configuration` class the same way as `SesClient` (→ [SesConfig.kt](../../examples/src/main/kotlin/com/example/accountservice/notification/infrastructure/SesConfig.kt)), and branches to LocalStack if `AWS_ENDPOINT_URL` is set.

```kotlin
// storage/infrastructure/StorageConfig.kt
@Configuration
class StorageConfig {

    @Bean
    fun s3Presigner(
        @Value("\${AWS_REGION:us-east-1}") region: String,
        @Value("\${AWS_ACCESS_KEY_ID:test}") accessKeyId: String,
        @Value("\${AWS_SECRET_ACCESS_KEY:test}") secretAccessKey: String,
        @Value("\${AWS_ENDPOINT_URL:}") endpointUrl: String,
    ): S3Presigner {
        val builder = S3Presigner.builder()
            .region(Region.of(region))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretAccessKey)))
        if (endpointUrl.isNotBlank()) builder.endpointOverride(URI.create(endpointUrl))
        return builder.build()
    }
}
```

Dependency to add to `build.gradle.kts` (not yet in `examples/`):

```kotlin
implementation("software.amazon.awssdk:s3:2.29.52")   // same SDK v2 version as SES
```

---

## The Entity stores only metadata

```kotlin
// account/domain/AccountAttachment.kt — example (if adding an attachment concept to the Account domain)
@Entity
@Table(name = "account_attachments")
class AccountAttachment protected constructor() {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        private set

    @Column(nullable = false, unique = true)
    var fileKey: String = ""       // the file's identifier within storage — use generateId() (hyphens removed, see aggregate-id.md)
        private set

    @Column(nullable = false)
    var accountId: String = ""
        private set

    @Column(nullable = false)
    var extension: String = ""
        private set

    companion object {
        fun create(accountId: String, extension: String): AccountAttachment =
            AccountAttachment().apply {
                this.fileKey = generateId()
                this.accountId = accountId
                this.extension = extension
            }
    }
}
```

The file binary itself is never kept in the DB or in application memory — only `fileKey`/`extension` are stored, and the client communicates directly with S3 via the presigned URL.

---

## Usage in an Application Service

```kotlin
// account/application/command/CreateAttachmentService.kt — example
@Service
@Transactional
class CreateAttachmentService(
    private val attachmentRepository: AccountAttachmentRepository,
    private val storageService: StorageService,
) {
    fun create(command: CreateAttachmentCommand): CreateAttachmentResult {
        val attachment = AccountAttachment.create(command.accountId, command.extension)
        attachmentRepository.save(attachment)
        val uploadUrl = storageService.generateUploadUrl("${command.accountId}/${attachment.fileKey}.${command.extension}")
        return CreateAttachmentResult(attachment.fileKey, command.extension, uploadUrl)
    }
}
```

---

## Local development — adding S3 to LocalStack

Currently `examples/docker-compose.yml` only enables `SERVICES: ses`. To add S3:

```yaml
# docker-compose.yml
localstack:
  environment:
    SERVICES: ses,s3   # comma-separated list
```

```bash
# localstack/init-s3.sh — add it the same way as init-ses.sh
#!/bin/sh
set -e
awslocal s3 mb s3://account-attachments
```

See [local-dev.md](local-dev.md) for details.

---

## Principles

- **The server never handles the file binary**: `S3Presigner` only issues URLs.
- **Reuse the Technical Service pattern**: a `StorageService` interface (Application) + `StorageServiceImpl` (Infrastructure) — the same structure as `NotificationService`.
- **The Entity holds only metadata**: `fileKey` (a 32-character hex string with no hyphens), `extension`.

### Related documents

- [directory-structure.md](directory-structure.md) — Technical Service pattern placement (the notification module example)
- [aggregate-id.md](aggregate-id.md) — the `fileKey` generation rule
- [local-dev.md](local-dev.md) — the LocalStack S3 setup

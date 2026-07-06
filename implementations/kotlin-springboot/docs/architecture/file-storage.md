# 파일 스토리지 — Kotlin Spring Boot

> 프레임워크 무관 원칙은 [root file-storage.md](../../../../docs/architecture/file-storage.md) 참조.

## 현재 상태

`examples/`에는 파일 업로드/다운로드 기능이 없다 — Account 도메인에 첨부파일 개념 자체가 없다. 아래는 이 저장소에 이미 있는 **Technical Service 패턴**(`notification/` 모듈, [directory-structure.md](directory-structure.md) 참조)을 그대로 재사용해 `StorageService`를 추가하는 방법이다. `NotificationService`/`NotificationServiceImpl` 쌍을 템플릿으로 삼는다.

---

## StorageService — 인터페이스 (Application 레이어)

```kotlin
// storage/application/service/StorageService.kt
package com.example.accountservice.storage.application.service

interface StorageService {
    fun generateUploadUrl(key: String): String
    fun generateDownloadUrl(key: String): String
}
```

`NotificationService`와 동일하게 Spring 무의존 `interface`다 — Application 레이어의 호출자(예: 첨부파일 Command Service)는 AWS SDK를 전혀 알지 못한다.

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

`S3Presigner`는 `SesClient`(→ [SesConfig.kt](../../examples/src/main/kotlin/com/example/accountservice/notification/infrastructure/SesConfig.kt))와 동일한 방식으로 `@Configuration` 클래스에서 Bean 등록하고, `AWS_ENDPOINT_URL`이 설정되어 있으면 LocalStack으로 분기한다.

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

`build.gradle.kts`에 추가해야 할 의존성 (`examples/`에는 아직 없음):

```kotlin
implementation("software.amazon.awssdk:s3:2.29.52")   // SES와 같은 SDK v2 버전
```

---

## Entity에는 메타데이터만 저장

```kotlin
// account/domain/AccountAttachment.kt — 예시 (Account 도메인에 첨부파일 개념을 추가한다면)
@Entity
@Table(name = "account_attachments")
class AccountAttachment protected constructor() {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        private set

    @Column(nullable = false, unique = true)
    var fileKey: String = ""       // 스토리지 내 파일 식별자 — generateId() 사용 (하이픈 제거, aggregate-id.md 참조)
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

파일 바이너리 자체는 절대 DB나 애플리케이션 메모리에 두지 않는다 — `fileKey`/`extension`만 저장하고, Presigned URL로 클라이언트가 S3와 직접 통신한다.

---

## Application Service에서 사용

```kotlin
// account/application/command/CreateAttachmentService.kt — 예시
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

## 로컬 개발 — LocalStack에 S3 추가

현재 `examples/docker-compose.yml`은 `SERVICES: ses`만 활성화한다. S3를 추가하려면:

```yaml
# docker-compose.yml
localstack:
  environment:
    SERVICES: ses,s3   # 쉼표로 나열
```

```bash
# localstack/init-s3.sh — init-ses.sh와 같은 방식으로 추가
#!/bin/sh
set -e
awslocal s3 mb s3://account-attachments
```

상세는 [local-dev.md](local-dev.md) 참조.

---

## 원칙

- **서버는 파일 바이너리를 처리하지 않는다**: `S3Presigner`로 URL만 발급.
- **Technical Service 패턴 재사용**: `StorageService` 인터페이스(Application) + `StorageServiceImpl`(Infrastructure) — `NotificationService`와 동일한 구조.
- **Entity는 메타데이터만**: `fileKey`(하이픈 없는 32자리 hex), `extension`.

### 관련 문서

- [directory-structure.md](directory-structure.md) — Technical Service 패턴 배치 (notification 모듈 예시)
- [aggregate-id.md](aggregate-id.md) — `fileKey` 생성 규칙
- [local-dev.md](local-dev.md) — LocalStack S3 구성

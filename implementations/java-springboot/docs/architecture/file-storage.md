# File Storage — the Presigned URL Pattern (Spring Boot / AWS SDK v2)

> For the framework-agnostic principles, see the root [file-storage.md](../../../../docs/architecture/file-storage.md).

## Current state

`examples/` has no file upload/download feature at all — the Account domain has no concept of attachments. `build.gradle` has no S3 SDK dependency either (only SES: `software.amazon.awssdk:ses`). Below is the pattern to follow if a file-attachment feature is added to this repository — it reuses the already-verified AWS SDK v2 conventions from `SesConfig`/`NotificationServiceImpl` as-is.

---

## `StorageService` — the Technical Service interface

```java
// application/service/StorageService.java — proposal
package com.example.accountservice.account.application.service;

public interface StorageService {
    String generateUploadUrl(String key);
    String generateDownloadUrl(String key);
}
```

```java
// infrastructure/StorageServiceImpl.java — S3 implementation (proposal)
@Component
public class StorageServiceImpl implements StorageService {

    private final S3Presigner presigner;
    private final String bucket;

    public StorageServiceImpl(
            @Value("${aws.region:us-east-1}") String region,
            @Value("${aws.endpoint-url:}") String endpointUrl,
            @Value("${aws.access-key-id:test}") String accessKeyId,
            @Value("${aws.secret-access-key:test}") String secretAccessKey,
            @Value("${s3.bucket}") String bucket
    ) {
        var builder = S3Presigner.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKeyId, secretAccessKey)));
        if (endpointUrl != null && !endpointUrl.isBlank()) {
            builder.endpointOverride(URI.create(endpointUrl));
        }
        this.presigner = builder.build();
        this.bucket = bucket;
    }

    @Override
    public String generateUploadUrl(String key) {
        PutObjectRequest objectRequest = PutObjectRequest.builder().bucket(bucket).key(key).build();
        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofHours(1))
                .putObjectRequest(objectRequest)
                .build();
        return presigner.presignPutObject(presignRequest).url().toString();
    }

    @Override
    public String generateDownloadUrl(String key) {
        GetObjectRequest objectRequest = GetObjectRequest.builder().bucket(bucket).key(key).build();
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofHours(1))
                .getObjectRequest(objectRequest)
                .build();
        return presigner.presignGetObject(presignRequest).url().toString();
    }
}
```

`S3Presigner` is a client dedicated to AWS SDK v2, existing separately from the regular `S3Client` purely for signing URLs — it follows the same SDK v2 builder pattern as `SesClient`/`SesClientBuilder` (see `SesConfig`). Explicitly limiting the signature's validity period, as with `Duration.ofHours(1)`, is important — a URL valid forever is a security risk.

```groovy
// build.gradle — would need to be added
implementation 'software.amazon.awssdk:s3'
```

---

## Only metadata is stored on the Entity

The Entity that owns a file only carries the file key and extension as columns — the file binary itself is never stored in the DB.

```java
// If domain needed an attachment concept (proposal, not currently applicable to Account)
@Embeddable
public record Attachment(String fileKey, String extension) {}
```

`fileKey` is generated with the same rule as [aggregate-id.md](aggregate-id.md) (32-character hex, no hyphens) and used as the file's identifier within storage.

---

## Usage from an Application Service

```java
// application/command/CreateAttachmentService.java — proposal
@Service
@RequiredArgsConstructor
@Transactional
public class CreateAttachmentService {
    private final StorageService storageService;
    private final AccountRepository accountRepository;

    public CreateAttachmentResult createAttachment(CreateAttachmentCommand command) {
        String fileKey = IdGenerator.generate();   // reuses the IdGenerator from aggregate-id.md
        String uploadUrl = storageService.generateUploadUrl(command.accountId() + "/" + fileKey + "." + command.extension());
        // only metadata is saved — the client PUTs the file binary directly to S3 via uploadUrl
        return new CreateAttachmentResult(fileKey, command.extension(), uploadUrl);
    }
}
```

The server never passes through the file binary at all — the client sends a `PUT` request directly to storage using `uploadUrl`.

---

## Local development — LocalStack S3

```yaml
# docker-compose.yml — if s3 is added to SERVICES (currently only SERVICES: ses)
localstack:
  environment:
    SERVICES: ses,s3
```

```bash
# localstack/init-s3.sh — if added
awslocal s3 mb s3://account-service-attachments
```

**A caveat with LocalStack + `S3Presigner`**: presigned URLs issued by LocalStack default to `path-style` (`http://localhost:4566/bucket-name/key`). Real AWS S3 defaults to virtual-hosted-style (`https://bucket-name.s3.amazonaws.com/key`), so for local development the `S3Presigner` builder needs `.serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())` added for URLs to work correctly against LocalStack — in production this setting should be turned off or applied conditionally (split by profile, the same way as the endpoint-branching pattern in [local-dev.md](local-dev.md)).

---

## Principles

- **The server never handles the file binary**: both upload and download go through Presigned URLs, communicating directly between client and S3.
- **Only metadata is stored in the DB**: `fileKey`, `extension`.
- **Abstract the storage implementation behind a `StorageService` interface**: the same Technical Service pattern as `NotificationService`.
- **Explicitly limit the signature's validity period**: e.g. `Duration.ofHours(1)`.
- **LocalStack requires path-style access**: `S3Configuration.pathStyleAccessEnabled(true)`.

---

### Related documents

- [aggregate-id.md](aggregate-id.md) — reusing the `fileKey` generation rule
- [local-dev.md](local-dev.md) — LocalStack S3 configuration
- [persistence.md](persistence.md) — common Entity column rules

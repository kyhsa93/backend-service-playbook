# 파일 스토리지 — Presigned URL 패턴 (Spring Boot / AWS SDK v2)

> 프레임워크 무관 원칙은 루트 [file-storage.md](../../../../docs/architecture/file-storage.md) 참고.

## 현재 상태

`examples/`에는 파일 업로드/다운로드 기능 자체가 없다 — Account 도메인에 첨부파일 개념이 없기 때문이다. `build.gradle`에 S3 SDK 의존성도 없다(SES만 있음: `software.amazon.awssdk:ses`). 아래는 이 저장소에 파일 첨부 기능을 추가할 때 따라야 할 패턴이며, 이미 검증된 `SesConfig`/`NotificationServiceImpl`의 AWS SDK v2 사용 관례를 그대로 재사용한다.

---

## `StorageService` — Technical Service 인터페이스

```java
// application/service/StorageService.java — 제안
package com.example.accountservice.account.application.service;

public interface StorageService {
    String generateUploadUrl(String key);
    String generateDownloadUrl(String key);
}
```

```java
// infrastructure/StorageServiceImpl.java — S3 구현체 (제안)
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

`S3Presigner`는 AWS SDK v2 전용 클라이언트로, 일반 `S3Client`와 별개로 URL 서명만을 위해 존재한다 — `SesClient`/`SesClientBuilder`(`SesConfig` 참고)와 같은 SDK v2 빌더 패턴을 그대로 따른다. `Duration.ofHours(1)`처럼 서명 유효기간을 명시적으로 제한하는 것이 중요하다 — 무기한 유효한 URL은 보안 위험이다.

```groovy
// build.gradle — 추가 필요
implementation 'software.amazon.awssdk:s3'
```

---

## Entity에는 메타데이터만 저장

파일을 소유하는 Entity는 파일 키와 확장자만 컬럼으로 갖는다 — 파일 바이너리 자체는 절대 DB에 저장하지 않는다.

```java
// domain에 첨부파일이 필요해지면 (제안, 현재 Account에는 해당 없음)
@Embeddable
public record Attachment(String fileKey, String extension) {}
```

`fileKey`는 [aggregate-id.md](aggregate-id.md)와 동일한 규칙(32자리 hex, 하이픈 없음)으로 생성해 스토리지 내 파일 식별자로 사용한다.

---

## Application Service에서 사용

```java
// application/command/CreateAttachmentService.java — 제안
@Service
@RequiredArgsConstructor
@Transactional
public class CreateAttachmentService {
    private final StorageService storageService;
    private final AccountRepository accountRepository;

    public CreateAttachmentResult createAttachment(CreateAttachmentCommand command) {
        String fileKey = IdGenerator.generate();   // aggregate-id.md의 IdGenerator 재사용
        String uploadUrl = storageService.generateUploadUrl(command.accountId() + "/" + fileKey + "." + command.extension());
        // 메타데이터만 저장 — 파일 바이너리는 클라이언트가 uploadUrl로 직접 S3에 PUT
        return new CreateAttachmentResult(fileKey, command.extension(), uploadUrl);
    }
}
```

서버는 파일 바이너리를 한 번도 경유하지 않는다 — 클라이언트가 `uploadUrl`로 스토리지에 직접 `PUT` 요청을 보낸다.

---

## 로컬 개발 — LocalStack S3

```yaml
# docker-compose.yml — SERVICES에 s3 추가 시 (현재 SERVICES: ses만 있음)
localstack:
  environment:
    SERVICES: ses,s3
```

```bash
# localstack/init-s3.sh — 추가 시
awslocal s3 mb s3://account-service-attachments
```

**LocalStack + `S3Presigner`의 주의점**: LocalStack이 발급하는 presigned URL은 기본적으로 `path-style`(`http://localhost:4566/bucket-name/key`)이다. 실제 AWS S3는 기본이 virtual-hosted-style(`https://bucket-name.s3.amazonaws.com/key`)이므로, 로컬 개발 시 `S3Presigner` 빌더에 `.serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())`를 추가해야 LocalStack에서 URL이 올바르게 동작한다 — 운영 환경에서는 이 설정을 끄거나 조건부로 적용한다([local-dev.md](local-dev.md)의 endpoint 분기 패턴과 동일하게 프로필별로 나눈다).

---

## 원칙

- **서버는 파일 바이너리를 처리하지 않는다**: 업로드/다운로드 모두 Presigned URL을 통해 클라이언트↔S3 직접 통신.
- **DB에는 메타데이터만 저장한다**: `fileKey`, `extension`.
- **`StorageService` 인터페이스로 스토리지 구현을 추상화한다**: `NotificationService`와 동일한 Technical Service 패턴.
- **서명 유효기간을 명시적으로 제한한다**: `Duration.ofHours(1)` 등.
- **LocalStack은 path-style 접근이 필요하다**: `S3Configuration.pathStyleAccessEnabled(true)`.

---

### 관련 문서

- [aggregate-id.md](aggregate-id.md) — `fileKey` 생성 규칙 재사용
- [local-dev.md](local-dev.md) — LocalStack S3 구성
- [persistence.md](persistence.md) — Entity 공통 컬럼 규칙

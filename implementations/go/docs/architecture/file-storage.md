# 파일 스토리지 (Go) — Presigned URL 패턴

원칙은 루트 [file-storage.md](../../../../docs/architecture/file-storage.md)를 따른다: 서버가 파일 바이너리를 직접 처리하지 않고, Presigned URL을 발급해 클라이언트가 스토리지(S3 등)와 직접 통신하도록 한다. **이 저장소의 Account 도메인 예제에는 파일 첨부 유스케이스가 없어 이 패턴이 구현되어 있지 않다.** 이 문서는 Go의 AWS SDK v2로 이 패턴을 어떻게 구현하는지 목표 형태로 제시하며, 이미 이 저장소에 구현된 SES 클라이언트 관용구를 그대로 재사용한다.

---

## StorageService — Technical Service 인터페이스

```go
// internal/application/command/storage_service.go — 목표 형태
package command

import "context"

type StorageService interface {
	GenerateUploadURL(ctx context.Context, key string) (string, error)
	GenerateDownloadURL(ctx context.Context, key string) (string, error)
}
```

---

## Infrastructure 구현체 — S3 Presigned URL

```go
// internal/infrastructure/storage/service.go — 목표 형태
package storage

import (
	"context"
	"fmt"
	"os"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/credentials"
	"github.com/aws/aws-sdk-go-v2/service/s3"
)

type Service struct {
	presignClient *s3.PresignClient
	bucket        string
	expires       time.Duration
}

func NewService(client *s3.Client, bucket string) *Service {
	return &Service{
		presignClient: s3.NewPresignClient(client),
		bucket:        bucket,
		expires:       1 * time.Hour,
	}
}

func (s *Service) GenerateUploadURL(ctx context.Context, key string) (string, error) {
	req, err := s.presignClient.PresignPutObject(ctx, &s3.PutObjectInput{
		Bucket: aws.String(s.bucket),
		Key:    aws.String(key),
	}, s3.WithPresignExpires(s.expires))
	if err != nil {
		return "", fmt.Errorf("presign upload url: %w", err)
	}
	return req.URL, nil
}

func (s *Service) GenerateDownloadURL(ctx context.Context, key string) (string, error) {
	req, err := s.presignClient.PresignGetObject(ctx, &s3.GetObjectInput{
		Bucket: aws.String(s.bucket),
		Key:    aws.String(key),
	}, s3.WithPresignExpires(s.expires))
	if err != nil {
		return "", fmt.Errorf("presign download url: %w", err)
	}
	return req.URL, nil
}

// NewS3Client는 ses_client.go와 동일한 관용구를 따른다: 명시적 정적 자격증명 +
// AWS_ENDPOINT_URL 분기. S3는 LocalStack에서 path-style 접근이 필요하다.
func NewS3Client() *s3.Client {
	region := os.Getenv("AWS_REGION")
	if region == "" {
		region = "us-east-1"
	}
	accessKeyID := os.Getenv("AWS_ACCESS_KEY_ID")
	if accessKeyID == "" {
		accessKeyID = "test"
	}
	secretAccessKey := os.Getenv("AWS_SECRET_ACCESS_KEY")
	if secretAccessKey == "" {
		secretAccessKey = "test"
	}

	return s3.New(s3.Options{
		Region:       region,
		Credentials:  credentials.NewStaticCredentialsProvider(accessKeyID, secretAccessKey, ""),
		BaseEndpoint: endpointOrNil(),
		UsePathStyle: os.Getenv("AWS_ENDPOINT_URL") != "", // LocalStack은 path-style 필요, 실 S3는 virtual-hosted-style
	})
}

func endpointOrNil() *string {
	if endpoint := os.Getenv("AWS_ENDPOINT_URL"); endpoint != "" {
		return &endpoint
	}
	return nil
}
```

`s3.NewPresignClient`가 반환하는 URL은 서명된 쿼리 파라미터를 포함한 완전한 URL이다 — 서버는 이 URL 문자열만 클라이언트에 반환하고, 실제 파일 바이너리는 절대 거치지 않는다.

---

## 메타데이터만 저장하는 Entity

파일을 소유하는 Aggregate/Entity는 파일 키와 확장자만 컬럼으로 갖는다. Account 도메인에 첨부파일이 생긴다면:

```go
// internal/domain/account/attachment.go — 목표 형태 (Account Aggregate 하위 Entity)
type Attachment struct {
	FileKey   string // common.NewID() — aggregate-id.md의 하이픈 제거 32자리 hex 규칙 적용
	AccountID string
	Extension string
	CreatedAt time.Time
}
```

파일명, 크기 등이 필요하면 컬럼을 추가한다 — 파일 자체는 절대 DB나 서버 메모리를 거치지 않는다.

---

## Application Handler에서 사용

```go
// internal/application/command/create_attachment_handler.go — 목표 형태
type CreateAttachmentHandler struct {
	repo    account.Repository
	storage StorageService
}

func (h *CreateAttachmentHandler) Handle(ctx context.Context, cmd CreateAttachmentCommand) (*CreateAttachmentResult, error) {
	fileKey := common.NewID()
	key := fmt.Sprintf("%s/%s.%s", cmd.AccountID, fileKey, cmd.Extension)

	uploadURL, err := h.storage.GenerateUploadURL(ctx, key)
	if err != nil {
		return nil, fmt.Errorf("generate upload url: %w", err)
	}

	if err := h.repo.SaveAttachment(ctx, account.Attachment{
		FileKey: fileKey, AccountID: cmd.AccountID, Extension: cmd.Extension, CreatedAt: time.Now(),
	}); err != nil {
		return nil, fmt.Errorf("save attachment metadata: %w", err)
	}

	return &CreateAttachmentResult{FileKey: fileKey, Extension: cmd.Extension, UploadURL: uploadURL}, nil
}
```

---

## 로컬 개발 — LocalStack

[local-dev.md](local-dev.md)의 `docker-compose.yml`에 `s3`를 추가한다(현재는 `ses`만 있음):

```yaml
localstack:
  image: localstack/localstack:3.0
  environment:
    SERVICES: ses,s3
```

```sh
#!/bin/sh
# localstack/init-s3.sh — init-ses.sh와 같은 방식
set -e
awslocal s3 mb s3://account-attachments
```

---

### 관련 문서

- [local-dev.md](local-dev.md) — LocalStack 서비스 추가 방법(SES에서 이미 실증됨)
- [aggregate-id.md](aggregate-id.md) — `fileKey` 생성 시 ID 형식 규칙
- [secret-manager.md](secret-manager.md) — 동일한 AWS SDK 클라이언트 생성 관용구
- [persistence.md](persistence.md) — Attachment 메타데이터 테이블의 공통 컬럼 규칙

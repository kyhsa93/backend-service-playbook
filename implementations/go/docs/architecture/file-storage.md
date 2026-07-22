# File Storage (Go) — the Presigned URL Pattern

The principle follows the root [file-storage.md](../../../../docs/architecture/file-storage.md): the server never handles file binaries directly — it issues a presigned URL and has the client communicate directly with storage (S3, etc.). **This repository's Account domain example has no file-attachment use case, so this pattern isn't implemented.** This document presents, as a target shape, how to implement this pattern with Go's AWS SDK v2, reusing the SES client idiom already implemented in this repository.

---

## StorageService — a Technical Service interface

```go
// internal/application/command/storage_service.go — target shape
package command

import "context"

type StorageService interface {
	GenerateUploadURL(ctx context.Context, key string) (string, error)
	GenerateDownloadURL(ctx context.Context, key string) (string, error)
}
```

---

## Infrastructure implementation — S3 presigned URL

```go
// internal/infrastructure/storage/service.go — target shape
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

// NewS3Client follows the same idiom as ses_client.go: explicit static credentials +
// an AWS_ENDPOINT_URL branch. S3 needs path-style access under LocalStack.
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
		UsePathStyle: os.Getenv("AWS_ENDPOINT_URL") != "", // LocalStack needs path-style, real S3 uses virtual-hosted-style
	})
}

func endpointOrNil() *string {
	if endpoint := os.Getenv("AWS_ENDPOINT_URL"); endpoint != "" {
		return &endpoint
	}
	return nil
}
```

The URL returned by `s3.NewPresignClient` is a complete URL that includes the signed query parameters — the server only returns this URL string to the client, and the actual file binary is never touched by it.

---

## An Entity that stores only metadata

The Aggregate/Entity that owns the file has only the file key and extension as columns. If the Account domain gains attachments:

```go
// internal/domain/account/attachment.go — target shape (a child Entity of the Account Aggregate)
type Attachment struct {
	FileKey   string // common.NewID() — applies the 32-character hyphen-removed hex rule from aggregate-id.md
	AccountID string
	Extension string
	CreatedAt time.Time
}
```

If the filename, size, etc. are needed, add columns for them — the file itself never passes through the DB or the server's memory.

---

## Usage in an Application Handler

```go
// internal/application/command/create_attachment_handler.go — target shape
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

## Local development — LocalStack

Add `s3` to the `docker-compose.yml` from [local-dev.md](local-dev.md) (currently only `ses` is present):

```yaml
localstack:
  image: localstack/localstack:3.0
  environment:
    SERVICES: ses,s3
```

```sh
#!/bin/sh
# localstack/init-s3.sh — the same approach as init-ses.sh
set -e
awslocal s3 mb s3://account-attachments
```

---

### Related documents

- [local-dev.md](local-dev.md) — how to add a LocalStack service (already demonstrated with SES)
- [aggregate-id.md](aggregate-id.md) — the ID format rule when generating `fileKey`
- [secret-manager.md](secret-manager.md) — the same AWS SDK client-creation idiom
- [persistence.md](persistence.md) — common column rules for the Attachment metadata table

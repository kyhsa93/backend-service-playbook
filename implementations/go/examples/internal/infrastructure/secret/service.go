package secret

import (
	"context"
	"fmt"
	"os"
	"sync"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/credentials"
	"github.com/aws/aws-sdk-go-v2/service/secretsmanager"
)

type cacheEntry struct {
	value     string
	expiresAt time.Time
}

// Service는 Secrets Manager를 조회하고 결과를 TTL 동안 메모리에 캐시한다.
// sync.Mutex로 보호한다 — 여러 고루틴(요청 핸들러)이 동시에 같은 키를 조회할 수 있다.
type Service struct {
	client *secretsmanager.Client
	ttl    time.Duration

	mu    sync.Mutex
	cache map[string]cacheEntry
}

func NewService(client *secretsmanager.Client, ttl time.Duration) *Service {
	return &Service{client: client, ttl: ttl, cache: make(map[string]cacheEntry)}
}

func (s *Service) GetSecret(ctx context.Context, secretID string) (string, error) {
	s.mu.Lock()
	if entry, ok := s.cache[secretID]; ok && time.Now().Before(entry.expiresAt) {
		s.mu.Unlock()
		return entry.value, nil
	}
	s.mu.Unlock()

	out, err := s.client.GetSecretValue(ctx, &secretsmanager.GetSecretValueInput{SecretId: aws.String(secretID)})
	if err != nil {
		return "", fmt.Errorf("get secret %s: %w", secretID, err)
	}
	value := aws.ToString(out.SecretString)

	s.mu.Lock()
	s.cache[secretID] = cacheEntry{value: value, expiresAt: time.Now().Add(s.ttl)}
	s.mu.Unlock()

	return value, nil
}

// NewSecretsManagerClient는 이 저장소의 ses_client.go와 동일한 관용구를 따른다:
// 명시적 정적 자격증명(IMDS 지연 회피) + AWS_ENDPOINT_URL로 LocalStack 분기.
func NewSecretsManagerClient() *secretsmanager.Client {
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

	options := secretsmanager.Options{
		Region:      region,
		Credentials: credentials.NewStaticCredentialsProvider(accessKeyID, secretAccessKey, ""),
	}
	if endpoint := os.Getenv("AWS_ENDPOINT_URL"); endpoint != "" {
		options.BaseEndpoint = &endpoint
	}
	return secretsmanager.New(options)
}

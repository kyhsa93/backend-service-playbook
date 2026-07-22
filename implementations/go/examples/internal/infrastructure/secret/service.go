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

// Service queries Secrets Manager and caches the result in memory for the
// TTL duration. It's protected by sync.Mutex — multiple goroutines (request
// handlers) may look up the same key concurrently.
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

// NewSecretsManagerClient follows the same idiom as this repository's
// ses_client.go: explicit static credentials (avoiding IMDS latency) +
// AWS_ENDPOINT_URL branching to LocalStack.
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

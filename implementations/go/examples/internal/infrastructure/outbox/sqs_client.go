package outbox

import (
	"os"

	"github.com/aws/aws-sdk-go-v2/credentials"
	"github.com/aws/aws-sdk-go-v2/service/sqs"
)

// NewSQSClient는 환경변수 기반으로 SQS 클라이언트를 생성한다 —
// notification.NewSESClient()와 정확히 같은 구성이다(AWS_ENDPOINT_URL 분기,
// 정적 test 자격증명 기본값). Poller/Consumer가 이 하나의 클라이언트를 공유한다.
//
// SDK의 기본 credential chain(IMDS 등)은 자격 증명이 없는 샌드박스/CI 환경에서
// 응답이 느리므로 사용하지 않고, 항상 명시적인 정적 자격 증명을 사용한다.
// AWS_ENDPOINT_URL이 설정되어 있으면 LocalStack 같은 엔드포인트로 우회한다.
func NewSQSClient() *sqs.Client {
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

	options := sqs.Options{
		Region:      region,
		Credentials: credentials.NewStaticCredentialsProvider(accessKeyID, secretAccessKey, ""),
	}

	if endpoint := os.Getenv("AWS_ENDPOINT_URL"); endpoint != "" {
		options.BaseEndpoint = &endpoint
	}

	return sqs.New(options)
}

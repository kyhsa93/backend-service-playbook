package notification

import (
	"os"

	"github.com/aws/aws-sdk-go-v2/credentials"
	"github.com/aws/aws-sdk-go-v2/service/ses"
)

// DefaultSenderEmail은 SES_SENDER_EMAIL 환경변수가 없을 때 사용하는 발신자 주소다.
const DefaultSenderEmail = "no-reply@backend-service-playbook.example.com"

// NewSESClient는 환경변수 기반으로 SES 클라이언트를 생성한다.
//
// SDK의 기본 credential chain(IMDS 등)은 자격 증명이 없는 샌드박스/CI 환경에서
// 응답이 느리므로 사용하지 않고, 항상 명시적인 정적 자격 증명을 사용한다.
// AWS_ENDPOINT_URL이 설정되어 있으면 LocalStack 같은 엔드포인트로 우회한다.
func NewSESClient() *ses.Client {
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

	options := ses.Options{
		Region:      region,
		Credentials: credentials.NewStaticCredentialsProvider(accessKeyID, secretAccessKey, ""),
	}

	if endpoint := os.Getenv("AWS_ENDPOINT_URL"); endpoint != "" {
		options.BaseEndpoint = &endpoint
	}

	return ses.New(options)
}

// SenderEmail은 SES_SENDER_EMAIL 환경변수 또는 기본 발신자 주소를 반환한다.
func SenderEmail() string {
	if sender := os.Getenv("SES_SENDER_EMAIL"); sender != "" {
		return sender
	}
	return DefaultSenderEmail
}

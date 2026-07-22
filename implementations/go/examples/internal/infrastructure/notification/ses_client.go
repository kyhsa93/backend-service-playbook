package notification

import (
	"os"

	"github.com/aws/aws-sdk-go-v2/credentials"
	"github.com/aws/aws-sdk-go-v2/service/ses"
)

// DefaultSenderEmail is the sender address used when the SES_SENDER_EMAIL environment variable is not set.
const DefaultSenderEmail = "no-reply@backend-service-playbook.example.com"

// NewSESClient creates an SES client based on environment variables.
//
// The SDK's default credential chain (IMDS, etc.) is slow to respond in
// sandbox/CI environments that have no credentials, so it's not used —
// explicit static credentials are always used instead. If AWS_ENDPOINT_URL
// is set, requests are routed to that endpoint (e.g. LocalStack) instead.
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

// SenderEmail returns the SES_SENDER_EMAIL environment variable or the default sender address.
func SenderEmail() string {
	if sender := os.Getenv("SES_SENDER_EMAIL"); sender != "" {
		return sender
	}
	return DefaultSenderEmail
}

package outbox

import (
	"os"

	"github.com/aws/aws-sdk-go-v2/credentials"
	"github.com/aws/aws-sdk-go-v2/service/sqs"
)

// NewSQSClient creates an SQS client based on environment variables —
// exactly the same configuration as notification.NewSESClient()
// (AWS_ENDPOINT_URL branching, static "test" credential defaults). The
// Poller/Consumer share this one client.
//
// The SDK's default credential chain (IMDS, etc.) is slow to respond in
// sandbox/CI environments that have no credentials, so it's not used —
// explicit static credentials are always used instead. If AWS_ENDPOINT_URL
// is set, requests are routed to that endpoint (e.g. LocalStack) instead.
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

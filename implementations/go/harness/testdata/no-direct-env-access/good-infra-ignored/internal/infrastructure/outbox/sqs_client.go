package outbox

import "os"

func queueURL() string {
	return os.Getenv("SQS_QUEUE_URL")
}

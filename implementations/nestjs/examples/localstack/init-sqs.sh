#!/bin/sh
set -e

# The shared Domain/Integration Event queue that OutboxPoller publishes to and OutboxConsumer
# receives from. Create the DLQ first, then attach it to the main queue via RedrivePolicy — a
# message that still fails after retrying past maxReceiveCount (3) (a poison payload/bug) gets
# isolated into the DLQ (docs/architecture/scheduling.md's DLQ convention).
awslocal sqs create-queue --queue-name domain-events-dlq

DLQ_ARN=$(awslocal sqs get-queue-attributes \
  --queue-url http://localhost:4566/000000000000/domain-events-dlq \
  --attribute-names QueueArn \
  --query 'Attributes.QueueArn' --output text)

awslocal sqs create-queue --queue-name domain-events \
  --attributes '{"RedrivePolicy":"{\"deadLetterTargetArn\":\"'"$DLQ_ARN"'\",\"maxReceiveCount\":\"3\"}"}'

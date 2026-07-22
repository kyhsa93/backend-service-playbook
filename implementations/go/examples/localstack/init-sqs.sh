#!/bin/sh
set -e

# The shared Domain/Integration Event queue that OutboxPoller publishes to
# and OutboxConsumer consumes from. Create the DLQ first, then link it to the
# main queue via RedrivePolicy — messages that still fail after retrying past
# maxReceiveCount (3) (toxic payload/bug) are isolated into the DLQ (the DLQ
# convention in docs/architecture/scheduling.md). Uses the same queue names
# and parameters as the nestjs implementation
# (implementations/nestjs/examples/localstack/init-sqs.sh) to keep
# cross-language consistency.
awslocal sqs create-queue --queue-name domain-events-dlq

DLQ_ARN=$(awslocal sqs get-queue-attributes \
  --queue-url http://localhost:4566/000000000000/domain-events-dlq \
  --attribute-names QueueArn \
  --query 'Attributes.QueueArn' --output text)

awslocal sqs create-queue --queue-name domain-events \
  --attributes '{"RedrivePolicy":"{\"deadLetterTargetArn\":\"'"$DLQ_ARN"'\",\"maxReceiveCount\":\"3\"}"}'

# The Task Queue (Scheduler -> task_outbox -> this queue -> Task Consumer) is
# a separate queue conceptually distinct from domain-events ("a fact
# occurred" vs. "command: perform X", docs/architecture/domain-events.md) —
# event_type and task_type are never mixed into the same queue. Its DLQ
# parameters match domain-events' (scheduling.md).
awslocal sqs create-queue --queue-name task-queue-dlq

TASK_DLQ_ARN=$(awslocal sqs get-queue-attributes \
  --queue-url http://localhost:4566/000000000000/task-queue-dlq \
  --attribute-names QueueArn \
  --query 'Attributes.QueueArn' --output text)

awslocal sqs create-queue --queue-name task-queue \
  --attributes '{"RedrivePolicy":"{\"deadLetterTargetArn\":\"'"$TASK_DLQ_ARN"'\",\"maxReceiveCount\":\"3\"}"}'

#!/bin/sh
set -e

# The shared Domain/Integration Event queue that OutboxPoller publishes to and OutboxConsumer
# consumes from. Create the DLQ first, then wire it to the main queue via RedrivePolicy — a
# message that still fails after exceeding maxReceiveCount(3) retries (a poison payload/bug) is
# isolated to the DLQ (see the DLQ convention in docs/architecture/scheduling.md).
awslocal sqs create-queue --queue-name domain-events-dlq

DLQ_ARN=$(awslocal sqs get-queue-attributes \
  --queue-url http://localhost:4566/000000000000/domain-events-dlq \
  --attribute-names QueueArn \
  --query 'Attributes.QueueArn' --output text)

awslocal sqs create-queue --queue-name domain-events \
  --attributes '{"RedrivePolicy":"{\"deadLetterTargetArn\":\"'"$DLQ_ARN"'\",\"maxReceiveCount\":\"3\"}"}'

# The Task Queue that TaskOutboxPoller publishes to and TaskConsumer consumes from. Unlike the
# Domain Event queue (a standard queue), this is a FIFO queue — even if multiple instances
# duplicate-enqueue on the same Cron tick, only one entry lands in the queue via
# MessageDeduplicationId (see "Cron safety with multiple instances" in
# docs/architecture/scheduling.md). A Task (a command) and a Domain Event (a fact) are
# conceptually different, so the queues themselves are kept separate (see
# docs/architecture/domain-events.md).
awslocal sqs create-queue --queue-name task-queue-dlq.fifo \
  --attributes '{"FifoQueue":"true"}'

TASK_DLQ_ARN=$(awslocal sqs get-queue-attributes \
  --queue-url http://localhost:4566/000000000000/task-queue-dlq.fifo \
  --attribute-names QueueArn \
  --query 'Attributes.QueueArn' --output text)

awslocal sqs create-queue --queue-name task-queue.fifo \
  --attributes '{"FifoQueue":"true","RedrivePolicy":"{\"deadLetterTargetArn\":\"'"$TASK_DLQ_ARN"'\",\"maxReceiveCount\":\"3\"}"}'

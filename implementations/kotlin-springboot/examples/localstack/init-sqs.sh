#!/bin/sh
set -e

# Shared Domain/Integration Event queue published by OutboxPoller and consumed by OutboxConsumer.
# Create the DLQ first, then attach it to the main queue via RedrivePolicy — messages that
# still fail after exceeding maxReceiveCount(3) retries (poison payload/bug) are isolated
# to the DLQ (DLQ convention in docs/architecture/scheduling.md).
awslocal sqs create-queue --queue-name domain-events-dlq

DLQ_ARN=$(awslocal sqs get-queue-attributes \
  --queue-url http://localhost:4566/000000000000/domain-events-dlq \
  --attribute-names QueueArn \
  --query 'Attributes.QueueArn' --output text)

awslocal sqs create-queue --queue-name domain-events \
  --attributes '{"RedrivePolicy":"{\"deadLetterTargetArn\":\"'"$DLQ_ARN"'\",\"maxReceiveCount\":\"3\"}"}'

# Task Queue published by TaskOutboxPoller and consumed by TaskQueueConsumer (scheduling.md).
# Deliberately kept as a separate queue from the Domain Event queue — a Task is a
# "command: perform X," and a FIFO queue is required to prevent duplicate enqueuing across
# multiple instances via a date/month-based deduplicationId (standard queues don't support
# MessageDeduplicationId). A FIFO queue's DLQ must also be FIFO and its name must end in
# ".fifo" (AWS constraint).
awslocal sqs create-queue --queue-name task-queue-dlq.fifo \
  --attributes '{"FifoQueue":"true"}'

TASK_DLQ_ARN=$(awslocal sqs get-queue-attributes \
  --queue-url http://localhost:4566/000000000000/task-queue-dlq.fifo \
  --attribute-names QueueArn \
  --query 'Attributes.QueueArn' --output text)

awslocal sqs create-queue --queue-name task-queue.fifo \
  --attributes '{"FifoQueue":"true","RedrivePolicy":"{\"deadLetterTargetArn\":\"'"$TASK_DLQ_ARN"'\",\"maxReceiveCount\":\"3\"}"}'

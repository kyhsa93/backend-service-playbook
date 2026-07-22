#!/bin/sh
set -e

# The Task Queue that TaskOutboxRelay publishes to and TaskQueueConsumer receives from.
# Separate from the Domain Event queue (init-sqs.sh) — a Task is a command ("perform X"), and
# since it must not be enqueued as a duplicate even when the Scheduler (Cron) enqueues at the
# same moment from multiple instances, FIFO + MessageDeduplicationId is used (see
# docs/architecture/scheduling.md, the Cron multi-instance safety section).
# Creating the DLQ first and attaching it to the main queue via RedrivePolicy is the same principle as domain-events.
awslocal sqs create-queue --queue-name task-queue-dlq.fifo \
  --attributes '{"FifoQueue":"true"}'

DLQ_ARN=$(awslocal sqs get-queue-attributes \
  --queue-url http://localhost:4566/000000000000/task-queue-dlq.fifo \
  --attribute-names QueueArn \
  --query 'Attributes.QueueArn' --output text)

awslocal sqs create-queue --queue-name task-queue.fifo \
  --attributes '{"FifoQueue":"true","RedrivePolicy":"{\"deadLetterTargetArn\":\"'"$DLQ_ARN"'\",\"maxReceiveCount\":\"3\"}"}'

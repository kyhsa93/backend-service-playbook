#!/bin/sh
set -e

# The shared Domain/Integration Event queue that OutboxPoller publishes to and
# OutboxConsumer receives from. The DLQ is created first, then linked to the main queue via
# RedrivePolicy — a message that still fails after retrying past maxReceiveCount (3) (a
# poison payload/bug) is isolated into the DLQ (the DLQ convention in
# docs/architecture/scheduling.md).
awslocal sqs create-queue --queue-name domain-events-dlq

DLQ_ARN=$(awslocal sqs get-queue-attributes \
  --queue-url http://localhost:4566/000000000000/domain-events-dlq \
  --attribute-names QueueArn \
  --query 'Attributes.QueueArn' --output text)

awslocal sqs create-queue --queue-name domain-events \
  --attributes '{"RedrivePolicy":"{\"deadLetterTargetArn\":\"'"$DLQ_ARN"'\",\"maxReceiveCount\":\"3\"}"}'

# The dedicated Task queue that TaskOutboxPoller publishes to and TaskConsumer receives
# from — a FIFO queue physically separate from the Domain Event queue (see "Task Queue vs
# Domain Event" in scheduling.md, domain-events.md: the difference in unit of meaning,
# "fact" vs. "command," plus the infrastructure difference of being a FIFO queue using
# MessageGroupId/MessageDeduplicationId). Likewise, the DLQ is created first and linked via
# RedrivePolicy.
awslocal sqs create-queue --queue-name tasks-dlq.fifo --attributes '{"FifoQueue":"true"}'

TASK_DLQ_ARN=$(awslocal sqs get-queue-attributes \
  --queue-url http://localhost:4566/000000000000/tasks-dlq.fifo \
  --attribute-names QueueArn \
  --query 'Attributes.QueueArn' --output text)

awslocal sqs create-queue --queue-name tasks.fifo \
  --attributes '{"FifoQueue":"true","RedrivePolicy":"{\"deadLetterTargetArn\":\"'"$TASK_DLQ_ARN"'\",\"maxReceiveCount\":\"3\"}"}'

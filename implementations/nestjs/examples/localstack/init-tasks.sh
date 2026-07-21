#!/bin/sh
set -e

# TaskOutboxRelay가 발행하고 TaskQueueConsumer가 수신하는 Task Queue. Domain Event 큐
# (init-sqs.sh)와 별개다 — Task는 "X를 수행하라"는 명령이고, 스케줄러(Cron)가 같은
# 시각에 여러 인스턴스에서 동시에 enqueue해도 중복 적재되지 않아야 하므로 FIFO +
# MessageDeduplicationId를 쓴다(docs/architecture/scheduling.md#cron-다중-인스턴스-안전성).
# DLQ를 먼저 만들고 메인 큐에 RedrivePolicy로 연결하는 것도 domain-events와 동일한 원칙.
awslocal sqs create-queue --queue-name task-queue-dlq.fifo \
  --attributes '{"FifoQueue":"true"}'

DLQ_ARN=$(awslocal sqs get-queue-attributes \
  --queue-url http://localhost:4566/000000000000/task-queue-dlq.fifo \
  --attribute-names QueueArn \
  --query 'Attributes.QueueArn' --output text)

awslocal sqs create-queue --queue-name task-queue.fifo \
  --attributes '{"FifoQueue":"true","RedrivePolicy":"{\"deadLetterTargetArn\":\"'"$DLQ_ARN"'\",\"maxReceiveCount\":\"3\"}"}'

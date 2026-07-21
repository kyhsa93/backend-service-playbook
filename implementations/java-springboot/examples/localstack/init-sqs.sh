#!/bin/sh
set -e

# OutboxPoller가 발행하고 OutboxConsumer가 수신하는 공유 Domain/Integration Event 큐.
# DLQ를 먼저 만들고, 메인 큐에 RedrivePolicy로 연결한다 — maxReceiveCount(3)를 넘겨
# 재시도해도 실패하는 메시지(독성 페이로드/버그)는 DLQ로 격리된다
# (docs/architecture/scheduling.md의 DLQ 컨벤션).
awslocal sqs create-queue --queue-name domain-events-dlq

DLQ_ARN=$(awslocal sqs get-queue-attributes \
  --queue-url http://localhost:4566/000000000000/domain-events-dlq \
  --attribute-names QueueArn \
  --query 'Attributes.QueueArn' --output text)

awslocal sqs create-queue --queue-name domain-events \
  --attributes '{"RedrivePolicy":"{\"deadLetterTargetArn\":\"'"$DLQ_ARN"'\",\"maxReceiveCount\":\"3\"}"}'

# TaskOutboxPoller가 발행하고 TaskConsumer가 수신하는 Task Queue. Domain Event 큐(표준 큐)와 달리 FIFO
# 큐다 — 여러 인스턴스가 같은 Cron tick에 중복 적재해도 MessageDeduplicationId로 큐에는 1건만 들어간다
# (docs/architecture/scheduling.md "Cron 다중 인스턴스 안전성"). Task(명령)와 Domain Event(사실)는
# 개념적으로 다르므로 큐 자체를 분리한다(docs/architecture/domain-events.md).
awslocal sqs create-queue --queue-name task-queue-dlq.fifo \
  --attributes '{"FifoQueue":"true"}'

TASK_DLQ_ARN=$(awslocal sqs get-queue-attributes \
  --queue-url http://localhost:4566/000000000000/task-queue-dlq.fifo \
  --attribute-names QueueArn \
  --query 'Attributes.QueueArn' --output text)

awslocal sqs create-queue --queue-name task-queue.fifo \
  --attributes '{"FifoQueue":"true","RedrivePolicy":"{\"deadLetterTargetArn\":\"'"$TASK_DLQ_ARN"'\",\"maxReceiveCount\":\"3\"}"}'

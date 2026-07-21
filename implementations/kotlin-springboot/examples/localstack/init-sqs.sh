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

# TaskOutboxPoller가 발행하고 TaskQueueConsumer가 수신하는 Task Queue(scheduling.md). Domain
# Event 큐와 의도적으로 분리한 별도 큐다 — Task는 "명령: X를 수행하라"이고 날짜/월 기반
# deduplicationId로 다중 인스턴스 중복 적재를 막아야 하므로 FIFO 큐가 필요하다(표준 큐는
# MessageDeduplicationId를 지원하지 않는다). FIFO 큐의 DLQ도 FIFO여야 하고 이름이 ".fifo"로
# 끝나야 한다(AWS 제약).
awslocal sqs create-queue --queue-name task-queue-dlq.fifo \
  --attributes '{"FifoQueue":"true"}'

TASK_DLQ_ARN=$(awslocal sqs get-queue-attributes \
  --queue-url http://localhost:4566/000000000000/task-queue-dlq.fifo \
  --attribute-names QueueArn \
  --query 'Attributes.QueueArn' --output text)

awslocal sqs create-queue --queue-name task-queue.fifo \
  --attributes '{"FifoQueue":"true","RedrivePolicy":"{\"deadLetterTargetArn\":\"'"$TASK_DLQ_ARN"'\",\"maxReceiveCount\":\"3\"}"}'

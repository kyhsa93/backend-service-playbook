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

# TaskOutboxPoller가 발행하고 TaskConsumer가 수신하는 전용 Task 큐 — Domain Event 큐와
# 물리적으로 분리된 FIFO 큐다(scheduling.md, domain-events.md "Task Queue vs Domain Event":
# "사실" 대 "명령"이라는 의미 단위 차이 + MessageGroupId/MessageDeduplicationId를 쓰는
# FIFO 큐라는 인프라 차이). 마찬가지로 DLQ를 먼저 만들고 RedrivePolicy로 연결한다.
awslocal sqs create-queue --queue-name tasks-dlq.fifo --attributes '{"FifoQueue":"true"}'

TASK_DLQ_ARN=$(awslocal sqs get-queue-attributes \
  --queue-url http://localhost:4566/000000000000/tasks-dlq.fifo \
  --attribute-names QueueArn \
  --query 'Attributes.QueueArn' --output text)

awslocal sqs create-queue --queue-name tasks.fifo \
  --attributes '{"FifoQueue":"true","RedrivePolicy":"{\"deadLetterTargetArn\":\"'"$TASK_DLQ_ARN"'\",\"maxReceiveCount\":\"3\"}"}'

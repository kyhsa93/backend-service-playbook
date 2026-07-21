package taskqueue

import (
	"context"
	"errors"
	"log/slog"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/sqs"
	"github.com/aws/aws-sdk-go-v2/service/sqs/types"
)

// Handler는 하나의 taskType을 처리하는 함수다. main.go에서 이 map의 값으로 등록되는
// 것은 항상 interface/task/의 Task Controller 메서드다 — Task Controller가 로직 없이
// Command Service로 위임하고, 여기서는 taskType 문자열로 그 함수를 찾아 호출하는
// 라우팅만 한다(outbox.Consumer가 application/event EventHandler를 찾아 호출하는 것과
// 동일한 역할 분리).
type Handler func(ctx context.Context, payload []byte) error

// Consumer는 Task Queue(SQS)를 long polling으로 수신 대기하다가 메시지를 받으면
// taskType(MessageAttributes)으로 handlers map에서 Task Controller를 찾아 호출한다.
//
// 핸들러 성공 → 메시지 삭제(ack). 핸들러 실패(또는 등록된 핸들러가 없음) → 삭제하지
// 않는다 — SQS의 visibility timeout이 지나면 자동 재전달된다(at-least-once,
// scheduling.md의 "Task 핸들러는 멱등하다"가 바로 이 재전달을 전제한다).
type Consumer struct {
	sqs      *sqs.Client
	queueURL string
	handlers map[string]Handler
}

func NewConsumer(sqsClient *sqs.Client, queueURL string, handlers map[string]Handler) *Consumer {
	return &Consumer{sqs: sqsClient, queueURL: queueURL, handlers: handlers}
}

// Run은 main()의 goroutine으로 단 한 번 시작되는 백그라운드 루프다(outbox.Consumer.Run과
// 동일한 모양). ctx가 취소되면(signal.NotifyContext) 진행 중인 ReceiveMessage(최대
// WaitTimeSeconds)를 끝까지 기다린 뒤 종료한다.
func (c *Consumer) Run(ctx context.Context) {
	for {
		if ctx.Err() != nil {
			return
		}

		result, err := c.sqs.ReceiveMessage(ctx, &sqs.ReceiveMessageInput{
			QueueUrl:              aws.String(c.queueURL),
			MaxNumberOfMessages:   10,
			MessageAttributeNames: []string{"taskType"},
			WaitTimeSeconds:       5,
		})
		if err != nil {
			if ctx.Err() != nil || errors.Is(err, context.Canceled) {
				return // graceful shutdown 중 ReceiveMessage가 취소된 경우
			}
			slog.ErrorContext(ctx, "Task Queue 수신 실패", "error", err)
			continue
		}

		for _, message := range result.Messages {
			c.handleMessage(ctx, message)
		}
	}
}

func (c *Consumer) handleMessage(ctx context.Context, message types.Message) {
	taskType := ""
	if attr, ok := message.MessageAttributes["taskType"]; ok && attr.StringValue != nil {
		taskType = *attr.StringValue
	}

	handler, ok := c.handlers[taskType]
	if !ok {
		slog.ErrorContext(ctx, "등록된 Task Controller를 찾지 못함 — 재시도로 남겨둠", "task_type", taskType)
		return // 삭제하지 않음 — visibility timeout 이후 재수신되어 재시도된다.
	}

	// 에러는 그대로 위로 전파된 것을 여기서 로깅만 하고 삭제하지 않는다 — Task
	// Controller 자신은 catch+변환하지 않는다(scheduling.md, "에러를 그대로 던진다").
	if err := handler(ctx, []byte(aws.ToString(message.Body))); err != nil {
		slog.ErrorContext(ctx, "Task 처리 실패", "task_type", taskType, "error", err)
		return // 삭제하지 않음 — visibility timeout 이후 재수신되어 재시도된다.
	}

	if _, err := c.sqs.DeleteMessage(ctx, &sqs.DeleteMessageInput{
		QueueUrl:      aws.String(c.queueURL),
		ReceiptHandle: message.ReceiptHandle,
	}); err != nil {
		slog.ErrorContext(ctx, "메시지 삭제 실패", "task_type", taskType, "error", err)
	}
}

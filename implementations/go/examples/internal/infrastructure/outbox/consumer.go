package outbox

import (
	"context"
	"errors"
	"log/slog"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/sqs"
	"github.com/aws/aws-sdk-go-v2/service/sqs/types"
)

// Handler는 하나의 event_type을 처리하는 함수다. payload는 Writer.SaveAll/
// Publisher.Publish가 저장한 JSON 원문 그대로 전달된다(역직렬화는 Handler
// 구현체의 책임).
type Handler func(ctx context.Context, payload []byte) error

// Consumer는 SQS를 long polling(ReceiveMessage의 WaitTimeSeconds)으로 수신
// 대기하다가 메시지를 받으면 eventType(MessageAttributes)으로 handlers map에서
// 핸들러를 찾아 호출한다.
//
// 핸들러 성공 → 메시지 삭제(ack). 핸들러 실패(또는 등록된 핸들러가 없음) → 삭제하지
// 않는다 — SQS의 visibility timeout이 지나면 자동 재전달된다(at-least-once). 이
// 저장소가 요구하는 EventHandler 멱등성(docs/architecture/domain-events.md)이 바로
// 이 재전달을 전제한다.
type Consumer struct {
	sqs      *sqs.Client
	queueURL string
	handlers map[string]Handler
}

func NewConsumer(sqsClient *sqs.Client, queueURL string, handlers map[string]Handler) *Consumer {
	return &Consumer{sqs: sqsClient, queueURL: queueURL, handlers: handlers}
}

// Run은 main()의 goroutine으로 단 한 번 시작되는 백그라운드 루프다 — 요청마다 새로
// 만들어지지 않는다. ctx가 취소되면(signal.NotifyContext) 진행 중인 ReceiveMessage
// (최대 WaitTimeSeconds)를 끝까지 기다린 뒤 종료한다.
func (c *Consumer) Run(ctx context.Context) {
	for {
		if ctx.Err() != nil {
			return
		}

		result, err := c.sqs.ReceiveMessage(ctx, &sqs.ReceiveMessageInput{
			QueueUrl:              aws.String(c.queueURL),
			MaxNumberOfMessages:   10,
			MessageAttributeNames: []string{"eventType"},
			WaitTimeSeconds:       5,
		})
		if err != nil {
			if ctx.Err() != nil || errors.Is(err, context.Canceled) {
				return // graceful shutdown 중 ReceiveMessage가 취소된 경우
			}
			slog.ErrorContext(ctx, "SQS 수신 실패", "error", err)
			continue
		}

		for _, message := range result.Messages {
			c.handleMessage(ctx, message)
		}
	}
}

func (c *Consumer) handleMessage(ctx context.Context, message types.Message) {
	eventType := ""
	if attr, ok := message.MessageAttributes["eventType"]; ok && attr.StringValue != nil {
		eventType = *attr.StringValue
	}

	handler, ok := c.handlers[eventType]
	if !ok {
		slog.ErrorContext(ctx, "등록된 핸들러를 찾지 못함 — 재시도로 남겨둠", "event_type", eventType)
		return // 삭제하지 않음 — visibility timeout 이후 재수신되어 재시도된다.
	}

	if err := handler(ctx, []byte(aws.ToString(message.Body))); err != nil {
		slog.ErrorContext(ctx, "이벤트 처리 실패", "event_type", eventType, "error", err)
		return // 삭제하지 않음 — visibility timeout 이후 재수신되어 재시도된다.
	}

	if _, err := c.sqs.DeleteMessage(ctx, &sqs.DeleteMessageInput{
		QueueUrl:      aws.String(c.queueURL),
		ReceiptHandle: message.ReceiptHandle,
	}); err != nil {
		slog.ErrorContext(ctx, "메시지 삭제 실패", "event_type", eventType, "error", err)
	}
}

package event

import "context"

// IntegrationPublisher는 Application EventHandler가 내부 Domain Event를 외부 BC용
// Integration Event로 변환해 Outbox에 적재하기 위한 포트다. 실제 구현은 outbox.Publisher가
// 맡는다(별도 Outbox row를 하나 insert). 여기서 필요로 하는 최소 시그니처만 선언한다.
//
// application/event/의 EventHandler는 이 포트를 통해 Outbox에 직접 쓸 수 있는 유일한 지점이다
// — Aggregate는 Integration Event를 직접 만들지 않고, 변환 지점은 항상 EventHandler다.
type IntegrationPublisher interface {
	Publish(ctx context.Context, eventName string, payload any) error
}

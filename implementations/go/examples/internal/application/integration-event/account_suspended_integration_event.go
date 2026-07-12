// Package integrationevent는 Account BC가 외부 BC(Card 등)에 공개하는 Integration Event
// 계약(versioned public contract)을 담는다. 내부 Domain Event(account.AccountSuspended 등)와
// 분리해 이름·스키마를 안정적으로 유지하고 버전을 명시한다 — EventName()이 Outbox row의
// event_type으로 사용되고, 수신 측(Card)은 이 문자열로 핸들러를 찾는다.
package integrationevent

import "time"

type AccountSuspendedV1 struct {
	AccountID   string    `json:"accountId"`
	SuspendedAt time.Time `json:"suspendedAt"`
}

func (AccountSuspendedV1) EventName() string { return "account.suspended.v1" }

// Package task는 Task Queue의 Interface 레이어 입력 어댑터(Task Controller)를 담는다
// — interface/http/의 HTTP Handler가 HTTP 요청을 받아 Application Service에
// 위임하듯, Task Controller는 Task Queue 메시지를 받아 Command Service를 호출한다
// (docs/architecture/scheduling.md, "Task Controller — Interface 레이어").
//
// 원칙은 두 가지뿐이다: (1) 로직 없이 Command로 위임한다 — 조건 분기나 비즈니스
// 규칙을 두지 않는다. (2) 에러를 그대로 던진다 — HTTP Handler처럼 catch해서
// 상태 코드로 변환하지 않는다. taskqueue.Consumer가 그 에러를 받아 삭제 여부(재시도/
// DLQ)를 결정한다.
package task

import (
	"context"
	"encoding/json"
	"fmt"

	"github.com/example/account-service/internal/application/command"
)

type interestTaskPayload struct {
	Date string `json:"date"`
}

// InterestTaskController는 "account.apply-interest" Task의 입력 어댑터다.
type InterestTaskController struct {
	handler *command.ApplyDailyInterestHandler
}

func NewInterestTaskController(handler *command.ApplyDailyInterestHandler) *InterestTaskController {
	return &InterestTaskController{handler: handler}
}

// HandleApplyInterest는 taskqueue.Handler 시그니처를 만족한다. 페이로드 역직렬화는
// outbox의 EventHandler들(application/event/*_event_handler.go)과 동일한 관용구다 —
// 이는 "번역"이지 비즈니스 로직이 아니다.
func (c *InterestTaskController) HandleApplyInterest(ctx context.Context, payload []byte) error {
	var p interestTaskPayload
	if err := json.Unmarshal(payload, &p); err != nil {
		return fmt.Errorf("unmarshal account.apply-interest task payload: %w", err)
	}
	return c.handler.Handle(ctx, command.ApplyDailyInterestCommand{Date: p.Date}) // 예외는 그대로 위로
}

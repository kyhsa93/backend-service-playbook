// Package logging은 log/slog를 감싸는 인프라 부속품(커스텀 Handler)을 담는다.
// 특정 도메인에 속하지 않고 main.go의 로거 초기화 한 곳에서만 쓰인다.
package logging

import (
	"context"
	"log/slog"

	"github.com/example/account-service/internal/common"
)

// CorrelationHandler는 slog.Handler를 감싸, ctx에 correlation ID가 실려 있으면
// 모든 로그 레코드에 자동으로 "correlation_id" 필드를 추가한다. 이 핸들러를 쓰면
// 각 로그 호출부(slog.InfoContext(ctx, ...) 등)에서 매번
// "correlation_id", middleware.CorrelationIDFromContext(ctx)를 직접 넘기지 않아도 된다
// (observability.md 참고).
type CorrelationHandler struct {
	slog.Handler
}

// NewCorrelationHandler는 next를 감싸는 CorrelationHandler를 만든다.
func NewCorrelationHandler(next slog.Handler) *CorrelationHandler {
	return &CorrelationHandler{Handler: next}
}

// Handle은 ctx에 correlation ID가 있으면 레코드에 필드를 추가한 뒤 내부 Handler에 위임한다.
func (h *CorrelationHandler) Handle(ctx context.Context, record slog.Record) error {
	if correlationID := common.CorrelationIDFromContext(ctx); correlationID != "" {
		record.AddAttrs(slog.String("correlation_id", correlationID))
	}
	return h.Handler.Handle(ctx, record)
}

// WithAttrs/WithGroup은 감싸고 있는 Handler가 반환한 새 Handler도 다시 CorrelationHandler로
// 감싸야 한다 — 그렇지 않으면 slog.Logger.With(...)/WithGroup(...)으로 만든 하위 로거가
// correlation_id 자동 주입을 잃어버린다.
func (h *CorrelationHandler) WithAttrs(attrs []slog.Attr) slog.Handler {
	return &CorrelationHandler{Handler: h.Handler.WithAttrs(attrs)}
}

func (h *CorrelationHandler) WithGroup(name string) slog.Handler {
	return &CorrelationHandler{Handler: h.Handler.WithGroup(name)}
}

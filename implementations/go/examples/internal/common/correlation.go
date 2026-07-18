package common

import "context"

type correlationIDKey struct{}

// WithCorrelationID는 correlationID를 담은 새 context를 반환한다.
// interface/http/middleware.CorrelationID가 요청마다 이 함수로 context를 만든다.
func WithCorrelationID(ctx context.Context, correlationID string) context.Context {
	return context.WithValue(ctx, correlationIDKey{}, correlationID)
}

// CorrelationIDFromContext는 WithCorrelationID로 실어 둔 correlation ID를 꺼낸다.
// 없으면 빈 문자열을 반환한다.
func CorrelationIDFromContext(ctx context.Context) string {
	if id, ok := ctx.Value(correlationIDKey{}).(string); ok {
		return id
	}
	return ""
}

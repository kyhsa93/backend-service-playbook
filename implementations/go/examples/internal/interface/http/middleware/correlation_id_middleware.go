package middleware

import (
	"context"
	"net/http"

	"github.com/google/uuid"
)

type correlationIDKey struct{}

// CorrelationID는 X-Correlation-Id 헤더를 읽어 context에 실어 전파하고, 응답 헤더로도
// 에코한다. 헤더가 없으면 새로 발급한다 — 분산 환경에서 요청 하나를 여러 서비스에 걸쳐
// 추적하기 위한 값이다(observability.md 참고).
func CorrelationID(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		correlationID := r.Header.Get("X-Correlation-Id")
		if correlationID == "" {
			correlationID = uuid.NewString()
		}
		ctx := context.WithValue(r.Context(), correlationIDKey{}, correlationID)
		w.Header().Set("X-Correlation-Id", correlationID)
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

// CorrelationIDFromContext는 CorrelationID 미들웨어를 통과한 요청의 context에서
// correlation ID를 꺼낸다. 없으면 빈 문자열을 반환한다.
func CorrelationIDFromContext(ctx context.Context) string {
	if id, ok := ctx.Value(correlationIDKey{}).(string); ok {
		return id
	}
	return ""
}

package middleware

import (
	"context"
	"net/http"

	"github.com/google/uuid"

	"github.com/example/account-service/internal/common"
)

// CorrelationID는 X-Correlation-Id 헤더를 읽어 context에 실어 전파하고, 응답 헤더로도
// 에코한다. 헤더가 없으면 새로 발급한다 — 분산 환경에서 요청 하나를 여러 서비스에 걸쳐
// 추적하기 위한 값이다(observability.md 참고). context에 값을 싣는 키는
// internal/common(도메인/레이어 어디서도 참조할 수 있는 프레임워크 무의존 패키지)에
// 두어, infrastructure/logging.CorrelationHandler도 같은 값을 읽을 수 있게 한다.
func CorrelationID(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		correlationID := r.Header.Get("X-Correlation-Id")
		if correlationID == "" {
			correlationID = uuid.NewString()
		}
		ctx := common.WithCorrelationID(r.Context(), correlationID)
		w.Header().Set("X-Correlation-Id", correlationID)
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

// CorrelationIDFromContext는 CorrelationID 미들웨어를 통과한 요청의 context에서
// correlation ID를 꺼낸다. 없으면 빈 문자열을 반환한다. common.CorrelationIDFromContext에
// 위임한다 — 이 함수는 하위 호환을 위해 유지한다.
func CorrelationIDFromContext(ctx context.Context) string {
	return common.CorrelationIDFromContext(ctx)
}

package middleware

import (
	"log/slog"
	"net/http"
	"time"
)

// statusRecorder는 http.ResponseWriter를 감싸 핸들러가 실제로 기록한 상태 코드를
// 관찰한다. net/http는 상태 코드를 조회하는 방법을 제공하지 않으므로(WriteHeader가
// 호출됐는지, 어떤 값으로 호출됐는지 표준 라이브러리 인터페이스만으로는 알 수 없다)
// 로깅 미들웨어가 직접 감싸서 기록해 둔다.
type statusRecorder struct {
	http.ResponseWriter
	status      int
	wroteHeader bool
}

func (rec *statusRecorder) WriteHeader(status int) {
	rec.status = status
	rec.wroteHeader = true
	rec.ResponseWriter.WriteHeader(status)
}

func (rec *statusRecorder) Write(b []byte) (int, error) {
	if !rec.wroteHeader {
		// net/http는 WriteHeader 없이 Write가 먼저 호출되면 200을 암묵적으로 기록한다 —
		// 로그에도 같은 기본값을 반영한다.
		rec.status = http.StatusOK
		rec.wroteHeader = true
	}
	return rec.ResponseWriter.Write(b)
}

// RequestLogging은 모든 요청에 대해 메서드/경로/상태 코드/소요 시간을 구조화 로그로
// 남기는 미들웨어다(cross-cutting-concerns.md). correlation_id 필드는 여기서 직접
// 넘기지 않는다 — main.go가 slog 기본 로거에 씌운 infrastructure/logging.CorrelationHandler가
// ctx에 실린 값을 모든 로그 레코드에 자동으로 추가한다(observability.md). RequestLogging은
// CorrelationID보다 안쪽(더 나중에 실행되도록)에 등록해야 그 ctx를 물려받는다.
func RequestLogging(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		rec := &statusRecorder{ResponseWriter: w}
		next.ServeHTTP(rec, r)
		slog.InfoContext(r.Context(), "http request handled",
			"method", r.Method,
			"path", r.URL.Path,
			"status", rec.status,
			"duration_ms", time.Since(start).Milliseconds(),
		)
	})
}

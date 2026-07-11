package http

import (
	"net/http"
	"sync/atomic"
)

// HealthHandler는 liveness/readiness 프로브를 제공한다. graceful-shutdown.md의
// 원칙(SIGTERM 수신 시 readiness를 먼저 실패로 전환)을 구현하기 위해 종료 상태를
// atomic 플래그로 들고 있다.
type HealthHandler struct {
	shuttingDown atomic.Bool
}

func NewHealthHandler() *HealthHandler {
	return &HealthHandler{}
}

// StartShutdown은 프로세스가 종료 절차에 들어갔음을 표시한다. main()이 SIGTERM/SIGINT를
// 받은 직후, srv.Shutdown(ctx)를 호출하기 전에 호출해야 한다 — readiness가 503으로
// 바뀐 뒤에야 오케스트레이터가 새 트래픽을 끊고, 그 사이에 서버는 여전히 응답할 수 있다.
func (h *HealthHandler) StartShutdown() {
	h.shuttingDown.Store(true)
}

// Live는 항상 200을 반환한다 — 종료 중이어도 프로세스 자체는 살아있으므로 liveness는
// 실패하면 안 된다(liveness 실패는 컨테이너 강제 재시작을 유발한다).
func (h *HealthHandler) Live(w http.ResponseWriter, r *http.Request) {
	w.WriteHeader(http.StatusOK)
}

// Ready는 StartShutdown() 호출 이후 503을 반환해 오케스트레이터가 이 인스턴스로의
// 새 요청 라우팅을 중단하도록 한다.
func (h *HealthHandler) Ready(w http.ResponseWriter, r *http.Request) {
	if h.shuttingDown.Load() {
		w.WriteHeader(http.StatusServiceUnavailable)
		return
	}
	w.WriteHeader(http.StatusOK)
}

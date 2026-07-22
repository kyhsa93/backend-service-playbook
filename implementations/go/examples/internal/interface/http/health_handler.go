package http

import (
	"net/http"
	"sync/atomic"
)

// HealthHandler provides liveness/readiness probes. It holds shutdown state
// as an atomic flag to implement the principle in graceful-shutdown.md
// (flip readiness to failing first on SIGTERM).
type HealthHandler struct {
	shuttingDown atomic.Bool
}

func NewHealthHandler() *HealthHandler {
	return &HealthHandler{}
}

// StartShutdown marks that the process has entered its shutdown procedure. It
// must be called right after main() receives SIGTERM/SIGINT, before calling
// srv.Shutdown(ctx) — only after readiness flips to 503 does the orchestrator
// cut off new traffic, and the server can still respond in the meantime.
func (h *HealthHandler) StartShutdown() {
	h.shuttingDown.Store(true)
}

// Live always returns 200 — the process itself is still alive even while
// shutting down, so liveness must never fail (a liveness failure triggers a
// forced container restart).
//
// @Summary		Liveness probe
// @Description	Reports whether the process itself is alive. Always returns 200, even while the process is shutting down — an orchestrator should use `ready`, not this, to decide whether to route traffic.
// @Tags			Health
// @Produce		json
// @Success		200	"The process is alive — returns 200 even while shutting down."
// @Router			/health/live [get]
func (h *HealthHandler) Live(w http.ResponseWriter, r *http.Request) {
	w.WriteHeader(http.StatusOK)
}

// Ready returns 503 after StartShutdown() has been called, so the
// orchestrator stops routing new requests to this instance.
//
// @Summary		Readiness probe
// @Description	Reports whether the process is ready to accept new requests. Used by an orchestrator to stop routing traffic during graceful shutdown.
// @Tags			Health
// @Produce		json
// @Success		200	"Ready to accept new requests."
// @Failure		503	"Shutdown has started and new requests are no longer accepted."
// @Router			/health/ready [get]
func (h *HealthHandler) Ready(w http.ResponseWriter, r *http.Request) {
	if h.shuttingDown.Load() {
		w.WriteHeader(http.StatusServiceUnavailable)
		return
	}
	w.WriteHeader(http.StatusOK)
}

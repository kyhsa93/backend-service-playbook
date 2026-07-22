package http

import "net/http"

// Live always returns 200 — the process is alive even while shutting down.
//
// @Summary		Liveness probe
// @Description	Reports whether the process itself is alive. Always returns 200.
// @Tags			Health
// @Success		200	"The process is alive."
// @Router			/health/live [get]
func (h *HealthHandler) Live(w http.ResponseWriter, r *http.Request) {
}

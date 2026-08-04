package middleware

import (
	"log/slog"
	"time"
)

// Logging measures how long the request took. The reading is never stored,
// only subtracted from a later one, so the location it carries is
// irrelevant — this file is outside the rule's scope and produces no
// finding at all.
func Logging() {
	start := time.Now()
	slog.Info("request handled", "duration_ms", time.Since(start).Milliseconds())
}

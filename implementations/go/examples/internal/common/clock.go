package common

import "time"

// Now returns the current time in UTC — the single reading every persisted,
// event-embedded, or period-arithmetic timestamp goes through.
//
// Bare time.Now() returns a time.Time carrying the host's local location, and
// lib/pq formats a time.Time using that location before sending it. Every
// timestamp column in this project is TIMESTAMP (without time zone, see
// migrations/), which stores the wall-clock digits it is handed without
// recording any offset — so the same code writes UTC on a CI runner and KST on
// a developer machine in Asia/Seoul, and the column ends up holding values
// that cannot be compared with each other. Month/day arithmetic built on such
// a value (statement periods, spending analysis windows, daily interest)
// shifts by the offset as well.
//
// Reading the clock only to measure elapsed time — durations, tickers,
// deadlines, backoff — is location-independent and stays on time.Now(); see
// the timezone rule in docs/conventions.md.
func Now() time.Time {
	return time.Now().UTC()
}

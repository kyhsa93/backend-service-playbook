// Process timezone pin — UTC. Unlike its siblings in this directory it exposes no getter: it is
// imported for its side effect alone, and it must be the VERY FIRST import of src/main.ts, ahead
// of @/tracing and everything else. It still belongs here because it is the one place the
// process's own environment is configured, and `src/config/*.config.ts` is the only place
// allowed to touch `process.env` (docs/architecture/config.md).
//
// Why it exists: every timestamp column in src/database/migrations is `TIMESTAMP` — WITHOUT
// TIME ZONE. The pg driver serializes a JS Date using the process's local UTC offset
// (`2026-08-05T09:00:00.000+09:00` on a host in Asia/Seoul, `...+00:00` under TZ=UTC), and a
// WITHOUT TIME ZONE column discards that offset and keeps only the wall clock. So without a
// pin, the instant that lands in the database is the HOST's wall clock — UTC on CI, KST on a
// laptop in Seoul, and the same row means two different things depending on where it was
// written.
//
// Why the fix is here and not at the call sites: a JS Date is already an absolute instant, so
// there is nothing to "convert to UTC" before saving. The divergence happens at the driver
// boundary, which means the only place that can fix it once for every call site is the
// process's timezone. Pinning it makes the serialization deterministic: the wall clock stored
// is always the UTC instant. Nothing in the codebase shifts a timestamp on write or on read —
// see docs/conventions.md, "Timezone rule — store UTC".
//
// Why it must run first: Node applies a runtime change to process.env.TZ (it re-runs tzset),
// but only for date operations that happen afterwards. @/tracing starts the OpenTelemetry SDK,
// which stamps spans, and any module below it may construct a Date or open a database pool at
// import time. Keeping the pin in its own side-effect module imported on line 1 makes that
// ordering requirement visible to a reader instead of burying it in a bare statement — the
// same shape, and the same reason, as src/tracing.ts.
//
// The container sets TZ=UTC too (see Dockerfile / docker-compose.yml) so the environment agrees
// with the process; this assignment is what guarantees it regardless of the environment.
process.env.TZ = 'UTC'

// Marks the file as a module rather than a global script. Nothing imports this binding.
export {}

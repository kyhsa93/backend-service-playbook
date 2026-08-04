package com.example.accountservice.common

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * The current instant as a UTC `LocalDateTime` — the single reading every timestamp that is
 * **persisted, embedded in a domain event, compared against a stored value, or used as the base for
 * period arithmetic** goes through.
 *
 * `LocalDateTime.now()` (no argument) resolves the clock against the JVM's *default* zone, and a
 * `LocalDateTime` carries no offset of its own, so the wall-clock digits it produces are the host's.
 * Every timestamp column in this project is `TIMESTAMP` — WITHOUT TIME ZONE (see
 * `src/main/resources/db/migration/`) — which stores exactly the digits it is handed and records no
 * offset. The same code therefore writes UTC on a CI runner and KST on a developer machine in
 * `Asia/Seoul`, and one column ends up holding values that cannot be compared with each other.
 * Month/day arithmetic built on such a value (card statement periods, spending-analysis and
 * spending-forecast windows, daily interest dates) shifts by the same offset, and near midnight a
 * date-only reading picks the wrong *day*, which changes period keys like `"2026-08"`.
 *
 * Reading the clock only to measure elapsed time — a latency stopwatch, a TTL/backoff deadline — is
 * location-independent and deliberately stays off this helper; `Instant` is already an absolute
 * instant and needs no conversion. See `docs/conventions.md`, "The timezone rule — store UTC".
 */
fun nowUtc(): LocalDateTime = LocalDateTime.now(ZoneOffset.UTC)

/**
 * Today's date on the UTC calendar — the date-only counterpart of [nowUtc], derived from it so the
 * two can never disagree.
 *
 * `LocalDate.now()` has the same defect as `LocalDateTime.now()` and one sharper consequence: within
 * the host offset of midnight it returns a different **day**, so an idempotency key or period key
 * built from it (`Account.lastInterestPaidAt`, a `yyyy-MM` payload) silently names the wrong day or
 * month depending on where the process runs.
 */
fun todayUtc(): LocalDate = nowUtc().toLocalDate()

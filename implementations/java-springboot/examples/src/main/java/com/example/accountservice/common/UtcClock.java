package com.example.accountservice.common;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;

/**
 * The single clock reading every persisted, event-embedded, or period-arithmetic timestamp goes
 * through — it always reports UTC, never the host's zone.
 *
 * <p>The no-argument {@code now()} factories on {@link LocalDateTime}, {@link LocalDate} and {@link
 * YearMonth} resolve the wall clock through {@link java.time.ZoneId#systemDefault()}, and the
 * values they return carry no offset of their own. Every timestamp column in this project is {@code
 * TIMESTAMP(6)} — without time zone, see {@code src/main/resources/db/migration/} — which stores
 * the wall-clock digits it is handed and records no offset either. So the same code writes UTC on a
 * CI runner and KST on a developer machine in {@code Asia/Seoul}, and one column ends up holding
 * values that cannot be compared with each other. Month/day arithmetic built on such a value (card
 * statement periods, spending analysis and forecast windows, daily interest accrual) shifts by the
 * same offset, and near midnight a day-granularity reading lands on the wrong day entirely — which
 * is what a period key like {@code "2026-08"} is derived from.
 *
 * <p>Reading the clock only to measure elapsed time — latency, TTL and backoff deadlines, scheduler
 * tick bookkeeping — is location-independent, so it stays on {@link java.time.Instant#now()}, which
 * is an absolute instant and carries no zone to get wrong. See the timezone rule in {@code
 * docs/conventions.md} for the full "what is not converted" list.
 */
public final class UtcClock {

    private UtcClock() {}

    /** The current date-time in UTC — the default reading for anything that leaves this process. */
    public static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    /** The current date in UTC — for day-granularity keys and daily deduplication IDs. */
    public static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    /** The current month in UTC — for month-granularity period keys such as {@code "2026-08"}. */
    public static YearMonth currentMonth() {
        return YearMonth.now(ZoneOffset.UTC);
    }
}

package harness.rules

import harness.*
import java.io.File

// A mention inside a KDoc/line comment (the helper's own documentation quotes the bare call as the
// thing to avoid) must not be counted as a violation, so comments are stripped before matching — the
// same rationale as the repository-naming and cqrs-pattern rules.
private val BLOCK_COMMENT = Regex("""/\*[\s\S]*?\*/""")
private val LINE_COMMENT = Regex("""//[^\n]*""")

// Empty parentheses only. LocalDateTime.now(ZoneOffset.UTC) / LocalDate.now(clock) pass an explicit
// zone or clock and are therefore not host-dependent, so they never match.
private val BARE_NOW = Regex("""\b(LocalDateTime|LocalDate)\.now\(\s*\)""")

private fun stripComments(content: String): String =
    content.replace(BLOCK_COMMENT, "").replace(LINE_COMMENT, "")

/**
 * utc-timestamp-source — a timestamp that is written to the database or embedded in a domain event
 * must be read in UTC (conventions.md, "The timezone rule — store UTC").
 *
 * `LocalDateTime.now()` / `LocalDate.now()` with no argument resolve against the JVM's *default*
 * zone, and a `LocalDateTime`/`LocalDate` carries no offset of its own, so the value produced is the
 * host's wall clock. A `TIMESTAMP` (WITHOUT TIME ZONE) column stores exactly the digits it is handed
 * and records no offset, so identical code writes UTC on a UTC CI runner and local time on a
 * developer machine — the column ends up holding values that cannot be compared with one another,
 * and period arithmetic derived from them shifts by the same offset. `LocalDate.now()` is sharper
 * still: within the host offset of midnight it names a different *day*, which silently changes a
 * period key like `"2026-08"` or a daily idempotency key.
 *
 * Scope — the rule reports only on `domain/` and `infrastructure/persistence/`. Those are the two
 * places where a clock reading is, by construction, a value that gets persisted or shipped inside a
 * domain event: the domain layer holds no timers or deadlines, and a persistence package exists to
 * write rows. Reading the clock to measure elapsed time (a request-latency stopwatch in a Filter or
 * HandlerInterceptor, a TTL-cache deadline, retry backoff) is location-independent and perfectly
 * legitimate; it lives in the `common/` and other `infrastructure/` packages that this scope
 * deliberately leaves alone, which is what keeps the rule from flagging it.
 */
fun checkUtcTimestampSource(rootPath: String): RuleResult {
    val root = File(rootPath)
    val result = RuleResult("utc-timestamp-source")
    var found = false

    for (f in collectKtFiles(root)) {
        if (!(f.pathContains("/domain/") || f.pathContains("/infrastructure/persistence/"))) continue
        found = true
        val rel = f.relTo(root)
        val code = stripComments(f.readText())
        val bare = BARE_NOW.findAll(code).count()
        if (bare > 0) {
            result.add(
                failFinding(
                    rel,
                    "reads the clock with a bare LocalDateTime.now()/LocalDate.now() ($bare call(s)) — the no-argument " +
                        "form resolves against the JVM's default zone, so the wall clock written into a TIMESTAMP " +
                        "(without time zone) column depends on the machine the process runs on. Route every persisted / " +
                        "domain-event / period-arithmetic timestamp through the project's shared UTC clock helper " +
                        "(common's nowUtc()/todayUtc(), i.e. LocalDateTime.now(ZoneOffset.UTC)). Elapsed-time " +
                        "measurement may keep reading the default clock and lives outside this rule's scope, " +
                        "domain/ and infrastructure/persistence/ (conventions.md)",
                ),
            )
        } else {
            result.add(passFinding("$rel (UTC clock source)"))
        }
    }

    if (!found) result.add(skipFinding("no Kotlin files under domain/, infrastructure/persistence/"))
    return result
}

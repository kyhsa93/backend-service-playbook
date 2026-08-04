package harness.rules;

import harness.Finding;
import harness.RuleResult;

import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static harness.JavaFiles.collectJavaFiles;
import static harness.JavaFiles.pathContains;
import static harness.JavaFiles.readText;
import static harness.JavaFiles.relTo;

/**
 * [33] A timestamp that is written to the database or embedded in a domain event must be read
 * in UTC, through the project's shared clock helper ({@code common/UtcClock.java}) — a bare
 * no-arg {@code LocalDateTime.now()}/{@code LocalDate.now()}/{@code YearMonth.now()} is
 * forbidden in {@code domain/} and {@code persistence/} (conventions.md — "the timezone rule").
 *
 * <p>Those no-arg factories resolve the wall clock through {@code ZoneId.systemDefault()}, and
 * the values they return carry no offset of their own. A {@code TIMESTAMP} (without time zone)
 * column stores the wall-clock digits it is handed and records no offset either, so identical
 * code writes UTC on a UTC CI runner and local time on a developer machine — the column ends up
 * holding values that cannot be compared with one another, and period arithmetic derived from
 * them (statement months, analysis windows, daily interest) shifts by the same offset.
 *
 * <p>Scope — only {@code domain/} and {@code persistence/} are reported on. Those are the two
 * places where a clock reading is, by construction, a value that gets persisted or shipped
 * inside a domain event: the Domain layer holds no timers or deadlines, and a persistence
 * package exists to write rows. Reading the clock to measure elapsed time (latency, TTL
 * expiry, retry backoff, scheduler tick bookkeeping) is location-independent and perfectly
 * legitimate, and it lives in the {@code web/} and other {@code infrastructure/} code that this
 * scope deliberately leaves alone — which is what keeps the rule from flagging it.
 *
 * <p>An explicit-zone reading such as {@code LocalDateTime.now(ZoneOffset.UTC)} is not a bare
 * reading and passes: the pattern requires empty parentheses.
 */
public final class UtcTimestampSource {
    private UtcTimestampSource() {
    }

    private static final Pattern BARE_NOW =
        Pattern.compile("\\b(LocalDateTime|LocalDate|YearMonth)\\s*\\.\\s*now\\s*\\(\\s*\\)");

    public static RuleResult check(String rootPath) {
        File root = new File(rootPath);
        RuleResult result = new RuleResult("utc-timestamp-source");
        boolean found = false;

        for (File f : collectJavaFiles(root)) {
            boolean inScope = pathContains(f, "/domain/") || pathContains(f, "/persistence/");
            if (!inScope) continue;
            found = true;
            String rel = relTo(f, root);
            String content = readText(f);

            Matcher m = BARE_NOW.matcher(content);
            if (m.find()) {
                result.add(Finding.fail(rel,
                    "Reads the clock with a bare " + m.group() + " — the no-arg now() factories resolve the "
                        + "wall clock through ZoneId.systemDefault(), so the value written to a TIMESTAMP "
                        + "(without time zone) column depends on the machine the process runs on. Route every "
                        + "persisted / domain-event / period-arithmetic timestamp through the shared UTC clock "
                        + "helper (common/UtcClock — now()/today()/currentMonth()). Elapsed-time measurement "
                        + "stays as it is and lives outside this rule's scope, domain/ and persistence/ "
                        + "(conventions.md)"));
            } else {
                result.add(Finding.pass(rel + " (confirmed no bare now() reading)"));
            }
        }

        if (!found) result.add(Finding.skip("No Java files under domain/ or persistence/"));
        return result;
    }
}

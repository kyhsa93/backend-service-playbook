package harness.rules;

import harness.Finding;
import harness.RuleResult;

import java.io.File;
import java.util.regex.Pattern;

import static harness.JavaFiles.collectJavaFiles;
import static harness.JavaFiles.pathContains;
import static harness.JavaFiles.readText;
import static harness.JavaFiles.relTo;

/**
 * [20] {@code @Scheduled}/{@code @EnableScheduling} may not be used in domain/ or
 * application/ (scheduling.md — "the Scheduler belongs in the Infrastructure layer: put
 * it under infrastructure/, not application/", "@Scheduled is forbidden in
 * Application/Domain").
 *
 * <p>This uses a blocklist approach — a whitelist of "allowed only inside
 * infrastructure/" would false-positive on this repository's two actual legitimate uses:
 * {@code outbox/OutboxPoller.java} (shared infrastructure package, but located at the
 * top-level {@code outbox/} rather than under a per-domain {@code infrastructure/}) and
 * {@code AccountServiceApplication.java} (the bootstrap entry point, where {@code
 * @EnableScheduling} necessarily sits in the top-level package). Both live outside
 * domain/·application/, so they must pass this rule, and the blocklist approach produces
 * exactly that result.
 */
public final class SchedulerInInfrastructureOnly {
    private SchedulerInInfrastructureOnly() {
    }

    private static final Pattern SCHEDULER_USAGE = Pattern.compile("@Scheduled\\b|@EnableScheduling\\b");

    public static RuleResult check(String rootPath) {
        File root = new File(rootPath);
        RuleResult result = new RuleResult("scheduler-in-infrastructure-only");
        boolean found = false;

        for (File f : collectJavaFiles(root)) {
            boolean inScope = pathContains(f, "/domain/") || pathContains(f, "/application/");
            if (!inScope) continue;
            found = true;
            String rel = relTo(f, root);
            String content = readText(f);

            if (SCHEDULER_USAGE.matcher(content).find()) {
                result.add(Finding.fail(rel,
                    "@Scheduled/@EnableScheduling is forbidden in domain/ or application/ — the Scheduler must be placed under infrastructure/ (scheduling.md)"));
            } else {
                result.add(Finding.pass(rel + " (confirmed no scheduler usage)"));
            }
        }

        if (!found) result.add(Finding.skip("No Java files under domain/ or application/"));
        return result;
    }
}

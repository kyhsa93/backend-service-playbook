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
 * [19] Logging is forbidden in the domain/ layer — neither {@code org.slf4j.*}, Lombok
 * {@code @Slf4j}, nor a direct {@code LoggerFactory.getLogger(...)} call may be used
 * inside domain/ (observability.md — "logging is forbidden in the Domain layer: Account
 * follows this principle — keep it that way when adding new domain methods too").
 */
public final class NoLoggingInDomain {
    private NoLoggingInDomain() {
    }

    private static final Pattern LOGGING_USAGE = Pattern.compile("org\\.slf4j|@Slf4j\\b|LoggerFactory");

    public static RuleResult check(String rootPath) {
        File root = new File(rootPath);
        RuleResult result = new RuleResult("no-logging-in-domain");
        boolean found = false;

        for (File f : collectJavaFiles(root)) {
            if (!pathContains(f, "/domain/")) continue;
            found = true;
            String rel = relTo(f, root);
            String content = readText(f);

            if (LOGGING_USAGE.matcher(content).find()) {
                result.add(Finding.fail(rel,
                    "Logging is forbidden in a domain/ class — neither slf4j/@Slf4j nor LoggerFactory may be used (observability.md)"));
            } else {
                result.add(Finding.pass(rel + " (confirmed no logging usage)"));
            }
        }

        if (!found) result.add(Finding.skip("No Java files under domain/"));
        return result;
    }
}

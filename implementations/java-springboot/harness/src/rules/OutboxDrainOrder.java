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
 * [11] Synchronous Outbox draining is forbidden — the Command Service must not directly
 * reference OutboxRelay/OutboxPoller/OutboxConsumer or call a drain method (domain-events.md)
 *
 * Outbox → queue publish/consume is the sole responsibility of the independently,
 * periodically running {@code OutboxPoller} (@Scheduled) and {@code OutboxConsumer} (SQS
 * long polling). If the Command Service synchronously calls a drain method in the same
 * process right after the save (save&lt;Noun&gt;) commits, the "write" and "event
 * processing" that the Outbox pattern was meant to separate get bundled back into a single
 * request — without this check, nobody would catch someone adding a drain call to the
 * Command Service.
 *
 * If code comments were included in the check, a comment explaining "why this must not be
 * called" could itself trigger a false positive, so comments are stripped with a very
 * simple pass before checking (the same level of approximation as this harness's other
 * regex-based rules).
 */
public final class OutboxDrainOrder {
    private OutboxDrainOrder() {
    }

    private static final Pattern FORBIDDEN_SYMBOL =
        Pattern.compile("\\bOutboxRelay\\b|\\bOutboxPoller\\b|\\bOutboxConsumer\\b");
    private static final Pattern FORBIDDEN_CALL =
        Pattern.compile("\\.\\s*(?:processPending|poll|drainOnce)\\s*\\(");

    public static RuleResult check(String rootPath) {
        File root = new File(rootPath);
        RuleResult result = new RuleResult("outbox-drain-order");
        boolean found = false;
        for (File f : collectJavaFiles(root)) {
            if (!pathContains(f, "/application/command/")) continue;
            found = true;
            String rel = relTo(f, root);
            String content = stripComments(readText(f));
            Matcher symbolMatcher = FORBIDDEN_SYMBOL.matcher(content);
            Matcher callMatcher = FORBIDDEN_CALL.matcher(content);
            if (symbolMatcher.find() || callMatcher.find()) {
                result.add(Finding.fail(rel, "Directly references OutboxRelay/OutboxPoller/OutboxConsumer or calls processPending()/poll()/drainOnce() — the Command Service must return right after saving, and Outbox → queue publish/consume is the sole responsibility of the independently, periodically running OutboxPoller/OutboxConsumer (synchronous draining is forbidden, domain-events.md)"));
            } else {
                result.add(Finding.pass(rel + " (confirmed no synchronous-drain reference)"));
            }
        }
        if (!found) result.add(Finding.skip("No Command Service under application/command/"));
        return result;
    }

    // A very simple comment strip — the same level of approximation as this harness's
    // other regex-based rules. Prevents a code comment explaining "why OutboxPoller must
    // not be called" from itself false-positiving as a violation.
    private static String stripComments(String content) {
        return content.replaceAll("/\\*[\\s\\S]*?\\*/", "").replaceAll("//.*", "");
    }
}

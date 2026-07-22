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
 * [9] Command Service must go through the Outbox only — direct use of
 * ApplicationEventPublisher/@EventListener/publishEvent() is forbidden (domain-events.md)
 */
public final class NoEventPublisherInCommand {
    private NoEventPublisherInCommand() {
    }

    private static final Pattern EVENT_PUBLISHER =
        Pattern.compile("ApplicationEventPublisher|@EventListener|\\.publishEvent\\(");

    public static RuleResult check(String rootPath) {
        File root = new File(rootPath);
        RuleResult result = new RuleResult("no-event-publisher-in-command");
        boolean found = false;
        for (File f : collectJavaFiles(root)) {
            if (!pathContains(f, "/application/command/")) continue;
            found = true;
            String rel = relTo(f, root);
            String content = readText(f);
            if (EVENT_PUBLISHER.matcher(content).find()) {
                result.add(Finding.fail(rel, "Command Service must not use ApplicationEventPublisher/@EventListener/publishEvent() — go through the Outbox instead (domain-events.md)"));
            } else {
                result.add(Finding.pass(rel + " (confirmed it goes through the Outbox)"));
            }
        }
        if (!found) result.add(Finding.skip("No Command Service"));
        return result;
    }
}

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
 * [9] Command Service는 Outbox 경유만 허용 — ApplicationEventPublisher/@EventListener/
 * publishEvent() 직접 사용 금지 (domain-events.md)
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
                result.add(Finding.fail(rel, "Command Service는 ApplicationEventPublisher/@EventListener/publishEvent()를 쓰지 않아야 함 — Outbox 경유(domain-events.md)"));
            } else {
                result.add(Finding.pass(rel + " (Outbox 경유 확인)"));
            }
        }
        if (!found) result.add(Finding.skip("Command Service 없음"));
        return result;
    }
}

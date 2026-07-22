package harness.rules;

import harness.Finding;
import harness.RuleResult;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

import static harness.JavaFiles.collectJavaFiles;
import static harness.JavaFiles.nameWithoutExtension;
import static harness.JavaFiles.pathContains;
import static harness.JavaFiles.readText;
import static harness.JavaFiles.relTo;

/** [8] event-placement */
public final class EventPlacement {
    private EventPlacement() {
    }

    public static RuleResult check(String rootPath) {
        File root = new File(rootPath);
        RuleResult result = new RuleResult("event-placement");
        boolean found = false;
        Set<String> reported = new HashSet<>();

        for (File f : collectJavaFiles(root)) {
            String name = nameWithoutExtension(f);
            String rel = relTo(f, root);
            if (name.endsWith("EventHandler")) {
                found = true;
                reported.add(f.getPath());
                // The Outbox pattern's dispatch contract (OutboxEventHandler, etc.) is not a
                // per-domain handler but shared infrastructure that operates together with
                // OutboxRelay, so placement under the outbox/ package is also allowed
                // (see domain-events.md, shared-modules.md).
                if (pathContains(f, "/application/event/") || pathContains(f, "/outbox/")) {
                    result.add(Finding.pass(rel + " (EventHandler)"));
                } else {
                    result.add(Finding.fail(rel, "EventHandler must be inside the application/event/ package (per-domain handlers) or the outbox/ package (Outbox dispatch contract)"));
                }
            } else if (name.matches(".*IntegrationEvent(V\\d+)?$")) {
                found = true;
                reported.add(f.getPath());
                if (pathContains(f, "/application/integrationevent/")) {
                    result.add(Finding.pass(rel + " (IntegrationEvent)"));
                } else {
                    result.add(Finding.fail(rel, "IntegrationEvent must be inside the application/integrationevent/ package"));
                }
            }
        }

        // @EventListener — synchronous domain-event subscription based on Spring's
        // ApplicationEventPublisher. Even if the file name doesn't follow the
        // *EventHandler/*IntegrationEvent convention, having the @EventListener
        // annotation makes it a de facto event handler, so the same application/event/
        // placement rule applies.
        for (File f : collectJavaFiles(root)) {
            if (reported.contains(f.getPath())) continue;
            String content = readText(f);
            if (!content.contains("@EventListener")) continue;
            found = true;
            String rel = relTo(f, root);
            if (pathContains(f, "/application/event/")) {
                result.add(Finding.pass(rel + " (@EventListener)"));
            } else {
                result.add(Finding.fail(rel, "A class using @EventListener must be inside the application/event/ package"));
            }
        }

        if (!found) result.add(Finding.skip("No event handlers"));
        return result;
    }
}

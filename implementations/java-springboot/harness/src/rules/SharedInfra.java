package harness.rules;

import harness.Finding;
import harness.RuleResult;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static harness.JavaFiles.collectJavaFiles;
import static harness.JavaFiles.readText;

/**
 * [7] shared-infra: outbox / task-queue
 *
 * The outbox trigger is decided by "is there code that actually references OutboxWriter" —
 * this avoids false-triggering on an unrelated file whose name happens to contain
 * "Outbox" (a past bug: the real files were already all inside outbox/, so the "find a
 * file outside it" condition was always false, meaning it only ever emitted SKIP and never
 * actually validated the outbox package). OutboxWriter is the "loads events into the
 * outbox table" component that exists regardless of whether synchronous draining (the old
 * approach) or Poller/Consumer-based async draining (current) is used, so it's used as the
 * trigger — OutboxRelay was removed in the switch to async, so it can no longer be used as
 * the trigger.
 */
public final class SharedInfra {
    private SharedInfra() {
    }

    // build/ mirrors the package structure of the compiled .class files, so without
    // excluding it, findDirsNamed would find the same-named directory twice — once under
    // src and once under build (excluded for the same reason as in JavaFiles.java).
    private static final Set<String> EXCLUDED_DIRS = Set.of("test", ".git", "build");

    public static RuleResult check(String rootPath) {
        File root = new File(rootPath);
        RuleResult result = new RuleResult("shared-infra");
        for (Finding f : checkOutboxPattern(root)) result.add(f);
        for (Finding f : checkTaskQueuePattern(root)) result.add(f);
        return result;
    }

    private static List<Finding> checkOutboxPattern(File root) {
        boolean usesOutboxWriter = collectJavaFiles(root).stream()
            .anyMatch(f -> readText(f).contains("OutboxWriter"));
        if (!usesOutboxWriter) {
            return List.of(Finding.skip("No outbox pattern"));
        }

        List<File> outboxDirs = new ArrayList<>();
        findDirsNamed(root, "outbox", outboxDirs);

        if (outboxDirs.isEmpty()) {
            return List.of(Finding.fail("outbox package", "References OutboxWriter but has no outbox/ package"));
        }

        boolean hasWriter = outboxDirs.stream().anyMatch(d -> new File(d, "OutboxWriter.java").isFile());
        boolean hasPoller = outboxDirs.stream().anyMatch(d -> new File(d, "OutboxPoller.java").isFile());
        boolean hasConsumer = outboxDirs.stream().anyMatch(d -> new File(d, "OutboxConsumer.java").isFile());
        if (hasWriter && hasPoller && hasConsumer) {
            return List.of(Finding.pass("outbox package (confirmed OutboxWriter/OutboxPoller/OutboxConsumer implementations)"));
        }
        return List.of(Finding.fail("outbox package", "outbox/ package exists but one or more of OutboxWriter.java/OutboxPoller.java/OutboxConsumer.java could not be found — outbox load (Writer) + queue publish (Poller) + queue consume (Consumer) must all be present (domain-events.md)"));
    }

    private static List<Finding> checkTaskQueuePattern(File root) {
        boolean hasTaskFile = collectJavaFiles(root).stream()
            .anyMatch(f -> f.getName().contains("TaskQueue"));
        if (!hasTaskFile) {
            return List.of(Finding.skip("No task-queue pattern"));
        }

        List<File> taskDirs = new ArrayList<>();
        findDirsNamed(root, "task-queue", taskDirs);
        findDirsNamed(root, "taskqueue", taskDirs);

        if (!taskDirs.isEmpty()) {
            return List.of(Finding.pass("task-queue package"));
        }
        return List.of(Finding.fail("task-queue package", "TaskQueue file exists but no task-queue/ package"));
    }

    private static void findDirsNamed(File dir, String name, List<File> out) {
        if (!dir.exists()) return;
        if (dir.isDirectory() && dir.getName().equals(name)) {
            out.add(dir);
        }
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory() && !EXCLUDED_DIRS.contains(child.getName())) {
                findDirsNamed(child, name, out);
            }
        }
    }
}

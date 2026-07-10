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
 * [7] shared-infra: outbox·task-queue
 *
 * outbox 트리거는 "OutboxRelay를 실제로 참조하는 코드가 있는가"로 판단한다 — 파일명에
 * 우연히 "Outbox"가 들어간 무관한 파일에 낚이지 않기 위함이다(과거 버그: 실제 파일들이
 * 이미 전부 outbox/ 안에 있어서 "밖에 있는 파일 찾기" 조건이 항상 거짓이 되어 SKIP만
 * 하고 outbox 패키지를 실질적으로 검증한 적이 없었다).
 */
public final class SharedInfra {
    private SharedInfra() {
    }

    private static final Set<String> EXCLUDED_DIRS = Set.of("test");

    public static RuleResult check(String rootPath) {
        File root = new File(rootPath);
        RuleResult result = new RuleResult("shared-infra");
        for (Finding f : checkOutboxPattern(root)) result.add(f);
        for (Finding f : checkTaskQueuePattern(root)) result.add(f);
        return result;
    }

    private static List<Finding> checkOutboxPattern(File root) {
        boolean usesOutboxRelay = collectJavaFiles(root).stream()
            .anyMatch(f -> readText(f).contains("OutboxRelay"));
        if (!usesOutboxRelay) {
            return List.of(Finding.skip("outbox 패턴 없음"));
        }

        List<File> outboxDirs = new ArrayList<>();
        findDirsNamed(root, "outbox", outboxDirs);

        if (outboxDirs.isEmpty()) {
            return List.of(Finding.fail("outbox 패키지", "OutboxRelay를 참조하지만 outbox/ 패키지가 없음"));
        }

        boolean hasWriter = outboxDirs.stream().anyMatch(d -> new File(d, "OutboxWriter.java").isFile());
        boolean hasRelay = outboxDirs.stream().anyMatch(d -> new File(d, "OutboxRelay.java").isFile());
        if (hasWriter && hasRelay) {
            return List.of(Finding.pass("outbox 패키지 (OutboxWriter/OutboxRelay 구현 확인)"));
        }
        return List.of(Finding.fail("outbox 패키지", "outbox/ 패키지는 있으나 OutboxWriter.java/OutboxRelay.java를 찾을 수 없음"));
    }

    private static List<Finding> checkTaskQueuePattern(File root) {
        boolean hasTaskFile = collectJavaFiles(root).stream()
            .anyMatch(f -> f.getName().contains("TaskQueue"));
        if (!hasTaskFile) {
            return List.of(Finding.skip("task-queue 패턴 없음"));
        }

        List<File> taskDirs = new ArrayList<>();
        findDirsNamed(root, "task-queue", taskDirs);
        findDirsNamed(root, "taskqueue", taskDirs);

        if (!taskDirs.isEmpty()) {
            return List.of(Finding.pass("task-queue 패키지"));
        }
        return List.of(Finding.fail("task-queue 패키지", "TaskQueue 파일이 있으나 task-queue/ 패키지 없음"));
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

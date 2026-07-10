package harness.rules;

import harness.Finding;
import harness.RuleResult;

import java.io.File;

import static harness.JavaFiles.collectJavaFiles;
import static harness.JavaFiles.nameWithoutExtension;
import static harness.JavaFiles.pathContains;
import static harness.JavaFiles.readText;
import static harness.JavaFiles.relTo;

/** [10] 트랜잭션 경계 — Command Service에는 없고 Repository.save()에 있어야 함 */
public final class TransactionBoundary {
    private TransactionBoundary() {
    }

    public static RuleResult check(String rootPath) {
        File root = new File(rootPath);
        RuleResult result = new RuleResult("transaction-boundary");
        boolean found = false;

        for (File f : collectJavaFiles(root)) {
            if (!pathContains(f, "/application/command/")) continue;
            found = true;
            String rel = relTo(f, root);
            String content = readText(f);
            if (content.contains("@Transactional")) {
                result.add(Finding.fail(rel, "Command Service에 @Transactional이 있으면 안 됨 — 트랜잭션 경계는 Repository.save()로 이관됨(domain-events.md, persistence.md)"));
            } else {
                result.add(Finding.pass(rel + " (트랜잭션 경계 미보유 확인)"));
            }
        }

        for (File f : collectJavaFiles(root)) {
            if (!nameWithoutExtension(f).endsWith("RepositoryImpl")) continue;
            String content = readText(f);
            if (!content.contains("Outbox")) continue;
            found = true;
            String rel = relTo(f, root);
            if (content.contains("@Transactional")) {
                result.add(Finding.pass(rel + " (Repository.save() 트랜잭션 경계 확인)"));
            } else {
                result.add(Finding.fail(rel, "Outbox를 저장하는 Repository 구현체에 @Transactional이 없음 — Aggregate 저장과 Outbox 적재가 원자적이지 않을 수 있음"));
            }
        }

        if (!found) result.add(Finding.skip("Command Service/Outbox 연동 Repository 구현체 없음"));
        return result;
    }
}

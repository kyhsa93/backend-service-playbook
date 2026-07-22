package harness.rules;

import harness.Finding;
import harness.RuleResult;

import java.io.File;

import static harness.JavaFiles.collectJavaFiles;
import static harness.JavaFiles.nameWithoutExtension;
import static harness.JavaFiles.pathContains;
import static harness.JavaFiles.readText;
import static harness.JavaFiles.relTo;

/** [10] Transaction boundary — must not be on the Command Service, must be on Repository.save() */
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
                result.add(Finding.fail(rel, "Command Service must not have @Transactional — the transaction boundary has moved to Repository.save() (domain-events.md, persistence.md)"));
            } else {
                result.add(Finding.pass(rel + " (confirmed it has no transaction boundary)"));
            }
        }

        for (File f : collectJavaFiles(root)) {
            if (!nameWithoutExtension(f).endsWith("RepositoryImpl")) continue;
            String content = readText(f);
            if (!content.contains("Outbox")) continue;
            found = true;
            String rel = relTo(f, root);
            if (content.contains("@Transactional")) {
                result.add(Finding.pass(rel + " (confirmed Repository.save() transaction boundary)"));
            } else {
                result.add(Finding.fail(rel, "The Repository implementation that saves to the Outbox is missing @Transactional — saving the Aggregate and loading the Outbox may not be atomic"));
            }
        }

        if (!found) result.add(Finding.skip("No Command Service / Outbox-integrated Repository implementation"));
        return result;
    }
}

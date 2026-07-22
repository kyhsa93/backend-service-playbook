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
 * [11] CQRS Query purity — files under application/query/ must not reference a
 * write-side Repository type (cqrs-pattern.md). This rule ports the same intent as the
 * nestjs harness's cqrs-pattern evaluator into this codebase's idiom (javac-based static
 * analysis).
 *
 * The "Repository" string detection runs only against the actual code, excluding comments
 * (Javadoc, etc.) — for example, {@code AccountQuery}'s Javadoc deliberately mentions
 * {@code AccountRepository} to explain the separation's intent, and such documentation
 * references must not be flagged as violations.
 */
public final class CqrsQueryPurity {
    private CqrsQueryPurity() {
    }

    private static final Pattern BLOCK_COMMENT = Pattern.compile("(?s)/\\*.*?\\*/");
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\n]*");

    public static RuleResult check(String rootPath) {
        File root = new File(rootPath);
        RuleResult result = new RuleResult("cqrs-query-purity");
        boolean found = false;

        for (File f : collectJavaFiles(root)) {
            if (!pathContains(f, "/application/query/")) continue;
            found = true;
            String rel = relTo(f, root);
            String code = stripComments(readText(f));
            if (code.contains("Repository")) {
                result.add(Finding.fail(rel,
                    "A file under application/query/ must not reference a write-side Repository type — it must depend on a Query-only interface (e.g. AccountQuery) instead (cqrs-pattern.md)"));
            } else {
                result.add(Finding.pass(rel + " (confirmed no Repository reference)"));
            }
        }

        if (!found) result.add(Finding.skip("No application/query/"));
        return result;
    }

    private static String stripComments(String content) {
        String withoutBlocks = BLOCK_COMMENT.matcher(content).replaceAll("");
        return LINE_COMMENT.matcher(withoutBlocks).replaceAll("");
    }
}

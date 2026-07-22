package harness.rules;

import harness.Finding;
import harness.RuleResult;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static harness.JavaFiles.collectJavaFiles;
import static harness.JavaFiles.nameWithoutExtension;
import static harness.JavaFiles.readText;
import static harness.JavaFiles.relTo;

/**
 * [26] The soft-delete filter — a JPA Entity with a {@code deletedAt} column must never
 * be hard-deleted, and every read must always apply {@code deletedAt IS NULL} (or an
 * equivalent filter) (persistence.md — "deletion defaults to soft delete; reads apply
 * deletedAt IS NULL by default").
 *
 * <p>The actual mechanism this repository uses was checked first: rather than attaching a
 * global Hibernate {@code @SQLRestriction}/{@code @Where} annotation on {@code
 * AccountJpaEntity}, it includes an {@code "a.deletedAt IS NULL"} condition every time in
 * the manually-assembled JPQL ({@code buildJpql()}) inside {@code AccountRepositoryImpl}.
 * Both mechanisms are recognized so that either one passes — if the Entity class has
 * {@code @SQLRestriction}/{@code @Where}, it's treated as a global filter and the
 * RepositoryImpl check is skipped; otherwise the deletedAt filter text is searched for
 * across the entire *RepositoryImpl.java file that has a find method (checked at the file
 * level, not the method level — because a structure like buildJpql, where a find method
 * delegates its JPQL to a separate private helper, is common, and looking only at the
 * method body would easily false-positive).
 *
 * <p>An Entity that has no {@code deletedAt} column at all (Card/Payment/Refund/Credential/
 * Transaction, etc. — Aggregates that have no delete use case yet) is naturally excluded
 * from this check, since the soft-delete requirement itself doesn't apply
 * (persistence.md explicitly allows this for Transaction too).
 */
public final class SoftDeleteFilter {
    private SoftDeleteFilter() {
    }

    private static final Pattern DELETED_AT_FIELD =
        Pattern.compile("(?m)^\\s*(?:@\\w+(?:\\([^)]*\\))?\\s*)*private\\s+\\S+\\s+deletedAt\\s*;");
    private static final Pattern GLOBAL_FILTER_ANNOTATION = Pattern.compile("@(SQLRestriction|Where)\\s*\\(");
    private static final Pattern FIND_METHOD_DECL = Pattern.compile("(?<![.\\w])\\bfind\\w*\\s*\\([^)]*\\)\\s*\\{");

    public static RuleResult check(String rootPath) {
        File root = new File(rootPath);
        RuleResult result = new RuleResult("soft-delete-filter");

        List<File> allFiles = collectJavaFiles(root);
        List<File> entitiesWithDeletedAt = new ArrayList<>();
        for (File f : allFiles) {
            if (!nameWithoutExtension(f).endsWith("JpaEntity")) continue;
            if (DELETED_AT_FIELD.matcher(stripComments(readText(f))).find()) {
                entitiesWithDeletedAt.add(f);
            }
        }

        if (entitiesWithDeletedAt.isEmpty()) {
            result.add(Finding.skip("No JPA Entity has a deletedAt column"));
            return result;
        }

        boolean anyChecked = false;
        for (File entityFile : entitiesWithDeletedAt) {
            String entityName = nameWithoutExtension(entityFile);
            String entityCode = stripComments(readText(entityFile));
            String entityRel = relTo(entityFile, root);

            if (GLOBAL_FILTER_ANNOTATION.matcher(entityCode).find()) {
                anyChecked = true;
                result.add(Finding.pass(entityRel + " (confirmed a @SQLRestriction/@Where global soft-delete filter)"));
                continue;
            }

            boolean referenced = false;
            for (File f : allFiles) {
                if (!nameWithoutExtension(f).endsWith("RepositoryImpl")) continue;
                String code = stripComments(readText(f));
                if (!code.contains(entityName)) continue;
                if (!FIND_METHOD_DECL.matcher(code).find()) continue; // not checked if there's no find method (write-only)
                referenced = true;
                anyChecked = true;

                String rel = relTo(f, root);
                if (containsDeletedAtFilter(code)) {
                    result.add(Finding.pass(rel + " (confirmed the deletedAt filter is applied when reading " + entityName + ")"));
                } else {
                    result.add(Finding.fail(rel,
                        "Has a find method that reads " + entityName + " but is missing deletedAt IS NULL (or an equivalent filter) — soft-deleted rows may be exposed as-is (persistence.md)"));
                }
            }

            if (!referenced) {
                result.add(Finding.skip(entityRel + " — no *RepositoryImpl.java has a find method"));
            }
        }

        if (!anyChecked) {
            result.add(Finding.skip("No *RepositoryImpl.java reads an Entity with a deletedAt column"));
        }
        return result;
    }

    private static boolean containsDeletedAtFilter(String code) {
        String lower = code.toLowerCase();
        return lower.contains("deletedat") || lower.contains("deleted_at");
    }

    private static String stripComments(String content) {
        return content.replaceAll("/\\*[\\s\\S]*?\\*/", "").replaceAll("//.*", "");
    }
}

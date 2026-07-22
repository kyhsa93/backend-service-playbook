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
 * [15] An Aggregate must not have JavaBean-style setters — state changes must always go
 * through a named domain method (e.g. {@code deposit()}/{@code suspend()}) (tactical-ddd.md).
 *
 * <p>This repository's Aggregates ({@code Account}/{@code Card}/{@code Payment}/{@code
 * Refund}) already change state only via a plain class + private constructor + static
 * factory ({@code create}/{@code reconstitute}) + named domain methods, so no violation
 * currently exists structurally — this rule is a regression guard: it catches someone
 * later turning an Aggregate into a mutable class with a Lombok {@code @Setter} or a
 * JavaBean {@code public void setX(...)} method.
 *
 * <p>A {@code record} (immutable, has no per-field setters to begin with) is naturally
 * excluded from the outset — only files with a {@code class} declaration are examined.
 * Treating every class under domain/ as an "Aggregate" may be an overgeneralization (a
 * Value Object can also be a class), but since a setter is a forbidden pattern either way,
 * targeting the whole of domain/ (files with a class declaration) carries no false-positive
 * risk — a blocklist approach.
 */
public final class AggregateNoPublicSetters {
    private AggregateNoPublicSetters() {
    }

    private static final Pattern CLASS_DECL = Pattern.compile("\\bclass\\s+(\\w+)");
    private static final Pattern PUBLIC_SETTER = Pattern.compile("\\bpublic\\s+void\\s+set[A-Z]\\w*\\s*\\(");
    private static final Pattern LOMBOK_SETTER = Pattern.compile("@Setter\\b");

    public static RuleResult check(String rootPath) {
        File root = new File(rootPath);
        RuleResult result = new RuleResult("aggregate-no-public-setters");
        boolean found = false;

        for (File f : collectJavaFiles(root)) {
            if (!pathContains(f, "/domain/")) continue;
            String code = stripComments(readText(f));
            Matcher classMatcher = CLASS_DECL.matcher(code);
            if (!classMatcher.find()) continue; // interface/record/enum — not checked (a record has no setters at all)

            found = true;
            String rel = relTo(f, root);

            if (LOMBOK_SETTER.matcher(code).find()) {
                result.add(Finding.fail(rel,
                    "Lombok @Setter is forbidden on a domain/ class — state changes must go through named domain methods only (tactical-ddd.md)"));
                continue;
            }

            Matcher setterMatcher = PUBLIC_SETTER.matcher(code);
            if (setterMatcher.find()) {
                result.add(Finding.fail(rel,
                    "A JavaBean-style public setter is forbidden on a domain/ class — state changes must go through named domain methods only (tactical-ddd.md)"));
            } else {
                result.add(Finding.pass(rel + " (confirmed no setters)"));
            }
        }

        if (!found) result.add(Finding.skip("No class-declaration files under domain/ (only record/interface/enum present)"));
        return result;
    }

    private static String stripComments(String content) {
        return content.replaceAll("/\\*[\\s\\S]*?\\*/", "").replaceAll("//.*", "");
    }
}

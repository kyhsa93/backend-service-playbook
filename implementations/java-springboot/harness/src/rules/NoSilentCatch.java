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
 * [21] An empty catch block ({@code catch (...) {}} with neither logging nor a rethrow) is
 * forbidden in application/·infrastructure/ — a failure that silently disappears becomes
 * unobservable (observability.md — "an error must always be logged before being thrown or
 * returned as a response").
 *
 * <p>A catch block that contains only a comment (no code) is still a silent failure and is
 * also caught — comments are stripped first, then only the "catch (...) { }" form
 * (whitespace only) is matched. This is deliberately narrow: if the block contains any code
 * at all (a logging call, or even an assignment explaining why it's being ignored), this
 * rule does not get involved — a broader match like "catch without logging" would carry a
 * high risk of false-positiving on an already-legitimate rethrow-only catch, so only the
 * most unambiguous pattern (a completely empty block) is blocklisted. It's been confirmed
 * that this pattern doesn't currently exist in this repository's application/·
 * infrastructure/ code — a pure regression guard.
 */
public final class NoSilentCatch {
    private NoSilentCatch() {
    }

    private static final Pattern EMPTY_CATCH = Pattern.compile("catch\\s*\\([^)]*\\)\\s*\\{\\s*\\}");

    public static RuleResult check(String rootPath) {
        File root = new File(rootPath);
        RuleResult result = new RuleResult("no-silent-catch");
        boolean found = false;

        for (File f : collectJavaFiles(root)) {
            boolean inScope = pathContains(f, "/application/") || pathContains(f, "/infrastructure/");
            if (!inScope) continue;
            found = true;
            String rel = relTo(f, root);
            String code = stripComments(readText(f));

            if (EMPTY_CATCH.matcher(code).find()) {
                result.add(Finding.fail(rel,
                    "An empty catch block is forbidden — do not silently ignore an exception; log it and handle it, or rethrow it (observability.md)"));
            } else {
                result.add(Finding.pass(rel + " (confirmed no empty catch block)"));
            }
        }

        if (!found) result.add(Finding.skip("No Java files under application/ or infrastructure/"));
        return result;
    }

    private static String stripComments(String content) {
        return content.replaceAll("/\\*[\\s\\S]*?\\*/", "").replaceAll("//.*", "");
    }
}

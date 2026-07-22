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
 * [27] Only typed errors are allowed — a root-level absolute principle (AGENTS.md): "type
 * errors as enums — no free-form strings." This repository's idiom is to throw a
 * per-domain typed exception class ({@code AccountException}, {@code PaymentException},
 * {@code CardException}, {@code AuthException}, etc., each holding an internal {@code
 * ErrorCode} enum), which {@code @ExceptionHandler} converts (error-handling.md).
 *
 * <p>Flags a failure whenever {@code domain/}·{@code application/} directly constructs
 * and throws a generic exception class ({@code RuntimeException}/{@code
 * IllegalStateException}/{@code IllegalArgumentException}/{@code
 * UnsupportedOperationException}/{@code Exception}) with a string message — this amounts
 * to expressing the error as a free-form string with no typed ErrorCode, a principle
 * violation.
 *
 * <p>The blocklist is scoped narrowly, matching only generic exception class names (a
 * class ending in a domain-specific name like {@code AccountException} does not match) —
 * to avoid broadly catching a legitimate generic-exception use the framework itself
 * requires (e.g. infrastructure code throwing {@code IllegalStateException} on a parse
 * failure), the scope is limited to domain/·application/ only. It's been confirmed that
 * this pattern doesn't currently exist in this repository's domain/·application/ code —
 * the {@code IllegalStateException} in outbox/ (OutboxWriter/OutboxConsumer)·common/config/
 * (SecretsEnvironmentPostProcessor) is an infrastructure-level fail-fast/deserialization
 * error, not a business-rule violation, and is out of scope. A pure regression guard.
 */
public final class TypedErrorsOnly {
    private TypedErrorsOnly() {
    }

    private static final Pattern GENERIC_THROW = Pattern.compile(
        "throw\\s+new\\s+(RuntimeException|IllegalStateException|IllegalArgumentException|UnsupportedOperationException|Exception)\\s*\\(");

    public static RuleResult check(String rootPath) {
        File root = new File(rootPath);
        RuleResult result = new RuleResult("typed-errors-only");
        boolean found = false;

        for (File f : collectJavaFiles(root)) {
            boolean inScope = pathContains(f, "/domain/") || pathContains(f, "/application/");
            if (!inScope) continue;
            found = true;
            String rel = relTo(f, root);
            String code = stripComments(readText(f));

            Matcher m = GENERIC_THROW.matcher(code);
            boolean violation = false;
            while (m.find()) {
                violation = true;
                result.add(Finding.fail(rel,
                    "Directly throws '" + m.group(1) + "' with a string — must throw a typed exception (a per-domain <Domain>Exception + ErrorCode enum) instead of a free-form string (error-handling.md, AGENTS.md)"));
            }
            if (!violation) {
                result.add(Finding.pass(rel + " (confirmed no direct throw of a generic exception)"));
            }
        }

        if (!found) result.add(Finding.skip("No Java files under domain/ or application/"));
        return result;
    }

    private static String stripComments(String content) {
        return content.replaceAll("/\\*[\\s\\S]*?\\*/", "").replaceAll("//.*", "");
    }
}

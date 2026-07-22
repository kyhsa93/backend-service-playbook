package harness.rules;

import harness.Finding;
import harness.RuleResult;

import java.io.File;

import static harness.JavaFiles.collectJavaFiles;
import static harness.JavaFiles.pathContains;
import static harness.JavaFiles.readText;
import static harness.JavaFiles.relTo;

/**
 * [16] Direct references between Aggregates are forbidden — when multiple Aggregates
 * exist within the same Bounded Context ({@code Payment}/{@code Refund} in the Payment
 * BC), one Aggregate must not directly hold the other Aggregate's type as a field or
 * constructor parameter. Only referencing an ID string (e.g. {@code paymentId}) is
 * allowed (domain-service.md — "Refund does not know the amount/status of the original
 * payment (it references it only via paymentId)").
 *
 * <p>Scoped narrowly to {@code payment/domain/} — because it's currently the only real
 * case in this repository where a single BC has more than one Aggregate (the Account/Card
 * BCs each have a single Aggregate). If a second Aggregate appears in another BC, this
 * rule needs to be generalized to that path too — for now it follows a blocklist approach
 * that precisely targets only the case that actually exists (to lower false-positive
 * risk, no general rule was written to infer "every Aggregate pair under domain/" — there
 * is no reliable static signal for whether something is an Aggregate).
 */
public final class NoCrossAggregateReference {
    private NoCrossAggregateReference() {
    }

    public static RuleResult check(String rootPath) {
        File root = new File(rootPath);
        RuleResult result = new RuleResult("no-cross-aggregate-reference");
        boolean found = false;

        for (File f : collectJavaFiles(root)) {
            if (!pathContains(f, "/payment/domain/")) continue;

            String fileName = f.getName();
            String otherAggregate;
            if (fileName.equals("Payment.java")) {
                otherAggregate = "Refund";
            } else if (fileName.equals("Refund.java")) {
                otherAggregate = "Payment";
            } else {
                continue;
            }

            found = true;
            String rel = relTo(f, root);
            String code = stripComments(readText(f));

            if (referencesType(code, otherAggregate)) {
                result.add(Finding.fail(rel,
                    "payment/domain/" + fileName + " directly references the other Aggregate type '" + otherAggregate
                        + "' — only an ID string reference is allowed (e.g. paymentId), domain-service.md"));
            } else {
                result.add(Finding.pass(rel + " (confirmed no reference to the other Aggregate type)"));
            }
        }

        if (!found) result.add(Finding.skip("No payment/domain/Payment.java or Refund.java"));
        return result;
    }

    // Manual scan instead of a \b word-boundary regex — must not false-positive on another
    // identifier that has the target name as a prefix, like PaymentException/PaymentStatus
    // (only matches when both sides are a non-alphanumeric/underscore boundary).
    private static boolean referencesType(String code, String typeName) {
        int idx = 0;
        while ((idx = code.indexOf(typeName, idx)) != -1) {
            boolean leftBoundary = idx == 0 || !isIdentifierChar(code.charAt(idx - 1));
            int end = idx + typeName.length();
            boolean rightBoundary = end >= code.length() || !isIdentifierChar(code.charAt(end));
            if (leftBoundary && rightBoundary) return true;
            idx = end;
        }
        return false;
    }

    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static String stripComments(String content) {
        return content.replaceAll("/\\*[\\s\\S]*?\\*/", "").replaceAll("//.*", "");
    }
}

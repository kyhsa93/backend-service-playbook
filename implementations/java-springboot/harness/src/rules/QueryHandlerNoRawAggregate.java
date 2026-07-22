package harness.rules;

import harness.Finding;
import harness.RuleResult;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static harness.JavaFiles.collectJavaFiles;
import static harness.JavaFiles.nameWithoutExtension;
import static harness.JavaFiles.pathContains;
import static harness.JavaFiles.readText;
import static harness.JavaFiles.relTo;

/**
 * [30] Fails if a Query Service/Controller directly returns a raw Aggregate — the Query
 * Service/Query interface under {@code application/query/} and the REST Controller under
 * {@code interfaces/} must always return a dedicated Result/DTO type, never a {@code
 * domain/} Aggregate/Entity class as-is (or wrapped in a generic like {@code
 * List<...>}/{@code ResponseEntity<...>}) (api-response.md).
 *
 * <p>Rather than hardcoding the list of "Aggregate/Entity classes," it dynamically
 * collects files declared as {@code public class} under {@code <bc>/domain/} whose name
 * doesn't end in {@code Exception}/{@code Service} (excluding domain exceptions and
 * Domain Services) — the real examples in this repository are {@code Account}/{@code
 * Transaction}/{@code Card}/{@code Payment}/{@code Refund}/{@code Credential}. No
 * hardcoded name needs updating even when a new domain is added.
 *
 * <p>Return-type matching uses an identifier-boundary check — it doesn't false-positive on
 * a legitimate DTO name that contains the Aggregate name as a substring, like {@code
 * GetAccountResult}, but it does catch a case wrapped in a generic argument, like {@code
 * List<Account>}.
 *
 * <p>Scanning is limited to {@code public} method declarations (the form {@code public
 * TYPE name(...)}) — a Query interface that omits {@code public} (as is idiomatic for
 * interface methods) is not checked; every Query interface in this repository returns a
 * {@code *WithCount} type, so there's no case of a violation being missed, and matching
 * broadly without requiring "public" would carry a high risk of false-positiving on a
 * constructor call like {@code new AccountException(...)}, so a narrow blocklist approach
 * was chosen instead.
 */
public final class QueryHandlerNoRawAggregate {
    private QueryHandlerNoRawAggregate() {
    }

    private static final Pattern DOMAIN_CLASS_DECL = Pattern.compile("\\bpublic\\s+(?:final\\s+|abstract\\s+)?class\\s+(\\w+)");
    private static final Pattern METHOD_DECL =
        Pattern.compile("public\\s+(?:static\\s+)?([A-Za-z_][\\w.]*(?:<[^(){};]*>)?(?:\\[\\])?)\\s+(\\w+)\\s*\\(");
    private static final Set<String> TYPE_KEYWORDS = Set.of("class", "record", "interface", "enum", "void");

    public static RuleResult check(String rootPath) {
        File root = new File(rootPath);
        RuleResult result = new RuleResult("query-handler-no-raw-aggregate");

        Set<String> aggregateNames = collectAggregateNames(root);
        if (aggregateNames.isEmpty()) {
            result.add(Finding.skip("No public class under <bc>/domain/ that can be treated as an Aggregate"));
            return result;
        }

        boolean found = false;
        for (File f : collectJavaFiles(root)) {
            boolean isQueryFile = pathContains(f, "/application/query/");
            boolean isController = pathContains(f, "/interfaces/") && f.getName().endsWith("Controller.java");
            if (!isQueryFile && !isController) continue;

            String code = stripComments(readText(f));
            String flattened = code.replaceAll("\\s+", " ");
            Matcher m = METHOD_DECL.matcher(flattened);

            boolean fileScanned = false;
            boolean fileHasViolation = false;
            String rel = relTo(f, root);

            while (m.find()) {
                String returnType = m.group(1);
                if (TYPE_KEYWORDS.contains(returnType)) continue; // exclude false detections like "public record X(" / "public class X"
                String methodName = m.group(2);
                fileScanned = true;

                String violatingAggregate = firstReferencedAggregate(returnType, aggregateNames);
                if (violatingAggregate != null) {
                    fileHasViolation = true;
                    result.add(Finding.fail(rel + "#" + methodName,
                        "The return type '" + returnType + "' directly exposes the raw Aggregate/Entity '" + violatingAggregate
                            + "' — must return a dedicated Result/DTO type instead (api-response.md)"));
                }
            }

            if (fileScanned) {
                found = true;
                if (!fileHasViolation) {
                    result.add(Finding.pass(rel + " (confirmed no raw Aggregate exposure)"));
                }
            }
        }

        if (!found) {
            result.add(Finding.skip("No public methods in application/query/ or interfaces/*Controller.java"));
        }
        return result;
    }

    private static Set<String> collectAggregateNames(File root) {
        Set<String> names = new LinkedHashSet<>();
        for (File f : collectJavaFiles(root)) {
            if (!pathContains(f, "/domain/")) continue;
            String code = stripComments(readText(f));
            Matcher m = DOMAIN_CLASS_DECL.matcher(code);
            if (!m.find()) continue;
            String className = m.group(1);
            if (className.endsWith("Exception") || className.endsWith("Service")) continue;
            names.add(className);
        }
        return names;
    }

    private static String firstReferencedAggregate(String returnType, Set<String> aggregateNames) {
        for (String name : aggregateNames) {
            if (referencesType(returnType, name)) return name;
        }
        return null;
    }

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

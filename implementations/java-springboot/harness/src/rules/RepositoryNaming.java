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
 * [12] Repository/Query method naming — a *Repository/*Query interface under domain/ or
 * application/query/ must only use the form find&lt;Noun&gt;s (plural, the same method is
 * reused for single-record lookups too)/save&lt;Noun&gt;/delete&lt;Noun&gt;
 * (repository-pattern.md). infrastructure/ (implementations, internal Spring Data JPA
 * derived-query methods) is not checked — that's an implementation detail, where
 * derived-query-style methods can legitimately exist.
 *
 * <p>Methods starting with {@code update} are also on the blocklist — because the root
 * docs state "no update methods on the Repository — look up the Aggregate, modify it via
 * a domain method, then save with save&lt;Noun&gt;" (repository-pattern.md).
 *
 * <p>Only a blocklist approach (narrow, precise matching) is used — a broad positive-match
 * grammar would false-positive on legitimate methods like {@code
 * hasTransactionWithReference}. This rule automates against a silent recurrence of a real
 * regression found across all 5 language implementations (Card's {@code
 * findByAccountIdAndStatusIn} + a bare {@code save}, etc.).
 */
public final class RepositoryNaming {
    private RepositoryNaming() {
    }

    private static final Pattern INTERFACE_DECL = Pattern.compile("\\binterface\\s+(\\w+)");
    private static final Pattern METHOD_DECL = Pattern.compile("(\\w+)\\s*\\([^)]*\\)\\s*;");

    // Only matches the form where By/by appears somewhere after "find" and is immediately
    // followed by an uppercase letter — this catches both findByAccountId and
    // findAccountsByOwnerId, while avoiding an accidental substring like
    // "findAccountsBypassCache" (which has no uppercase-letter boundary).
    private static final Pattern FIND_BY = Pattern.compile("^[Ff]ind\\w*[Bb]y[A-Z]");

    public static RuleResult check(String rootPath) {
        File root = new File(rootPath);
        RuleResult result = new RuleResult("repository-naming");
        boolean found = false;

        for (File f : collectJavaFiles(root)) {
            if (!pathContains(f, "/domain/") && !pathContains(f, "/application/query/")) continue;

            String code = stripComments(readText(f));
            Matcher ifaceMatcher = INTERFACE_DECL.matcher(code);
            if (!ifaceMatcher.find()) continue; // record/class/enum (e.g. AccountFindQuery) — not checked

            String interfaceName = ifaceMatcher.group(1);
            if (!interfaceName.endsWith("Repository") && !interfaceName.endsWith("Query")) continue;

            found = true;
            String rel = relTo(f, root);
            String flattened = code.replaceAll("\\s+", " ");
            Matcher methodMatcher = METHOD_DECL.matcher(flattened);

            boolean fileHasViolation = false;
            while (methodMatcher.find()) {
                String methodName = methodMatcher.group(1);
                String violation = classify(methodName);
                if (violation != null) {
                    fileHasViolation = true;
                    result.add(Finding.fail(rel + "#" + methodName,
                        violation + " — a Repository/Query interface method must take the form find<Noun>s (plural, reused for single-record lookups too)/save<Noun>/delete<Noun> (repository-pattern.md)"));
                }
            }
            if (!fileHasViolation) {
                result.add(Finding.pass(rel + " (" + interfaceName + " naming convention confirmed)"));
            }
        }

        if (!found) {
            result.add(Finding.skip("No *Repository/*Query interface under domain/ or application/query/"));
        }
        return result;
    }

    private static String classify(String methodName) {
        if (FIND_BY.matcher(methodName).find()) {
            return "'" + methodName + "' mimics a findBy... derived-query style method (Spring Data idiom)";
        }
        if (methodName.equals("findAll")) {
            return "'" + methodName + "' is a bare findAll with no target noun";
        }
        if (methodName.startsWith("count")) {
            return "'" + methodName + "' is a count-only method — a count must be returned alongside the find result as *WithCount, not as a separate method";
        }
        if (methodName.equals("save")) {
            return "'" + methodName + "' is a bare save with no target noun";
        }
        if (methodName.equals("delete")) {
            return "'" + methodName + "' is a bare delete with no target noun";
        }
        if (methodName.startsWith("update")) {
            return "'" + methodName + "' is a separate update method, which is forbidden on a Repository — look it up, modify it via the Aggregate's domain method, then save it with save<Noun>";
        }
        return null;
    }

    private static String stripComments(String content) {
        return content.replaceAll("/\\*[\\s\\S]*?\\*/", "").replaceAll("//.*", "");
    }
}

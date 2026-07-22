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
 * [14] The Interfaces layer (REST Controller, etc.) may not directly import
 * infrastructure/ — it must go through Application (the Command/Query Service in
 * `application/command`/`application/query`) instead (layer-architecture.md — {@code
 * interfaces/rest -> application -> domain}; infrastructure implements domain, but
 * interfaces must not know about it directly).
 *
 * <p>Flags a violation whenever any {@code infrastructure/} segment appears in an import
 * path, regardless of whether it's the same domain or a sibling domain — the same
 * path-based approach as domain-layer-isolation.
 */
public final class InterfaceNoInfrastructure {
    private InterfaceNoInfrastructure() {
    }

    private static final Pattern IMPORT_LINE = Pattern.compile("^import\\s+(?:static\\s+)?([\\w.]+);", Pattern.MULTILINE);
    private static final Pattern INFRASTRUCTURE_SEGMENT = Pattern.compile("\\.infrastructure\\.");

    public static RuleResult check(String rootPath) {
        File root = new File(rootPath);
        RuleResult result = new RuleResult("interface-no-infrastructure");
        boolean found = false;

        for (File f : collectJavaFiles(root)) {
            if (!pathContains(f, "/interfaces/")) continue;
            found = true;
            String rel = relTo(f, root);
            String code = readText(f);

            Matcher importMatcher = IMPORT_LINE.matcher(code);
            String violation = null;
            while (importMatcher.find()) {
                String imported = importMatcher.group(1);
                if (INFRASTRUCTURE_SEGMENT.matcher(imported).find()) {
                    violation = imported;
                    break;
                }
            }

            if (violation != null) {
                result.add(Finding.fail(rel,
                    "An interfaces/ class directly imports infrastructure/ — '" + violation
                        + "' (must follow the interfaces -> application -> domain order; infrastructure must be reached through application, layer-architecture.md)"));
            } else {
                result.add(Finding.pass(rel + " (confirmed no infrastructure reference)"));
            }
        }

        if (!found) result.add(Finding.skip("No Java files under interfaces/"));
        return result;
    }
}

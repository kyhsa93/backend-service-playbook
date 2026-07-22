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
 * [13] Domain layer isolation — a file inside {@code <domain>/domain/} may not import its
 * own {@code application/}/{@code infrastructure/}/{@code interfaces/}, nor the same
 * layers of a sibling domain (layer-architecture.md — "upper layers may depend on lower
 * layers, but not the reverse").
 *
 * <p>This is a path (package)-based check — unlike the {@code domain-purity} rule, which
 * only blocklists specific Spring-annotation strings (`@Service`/`@Component`/...), this
 * rule hardcodes no framework name at all and looks only at "does the import statement's
 * package path contain an application/infrastructure/interfaces segment" — so it also
 * catches domain referencing an arbitrary upper-layer class that isn't Spring-related
 * (a more structural/comprehensive check).
 */
public final class DomainLayerIsolation {
    private DomainLayerIsolation() {
    }

    private static final Pattern IMPORT_LINE = Pattern.compile("^import\\s+(?:static\\s+)?([\\w.]+);", Pattern.MULTILINE);
    private static final Pattern FORBIDDEN_SEGMENT = Pattern.compile("\\.(application|infrastructure|interfaces)\\.");

    public static RuleResult check(String rootPath) {
        File root = new File(rootPath);
        RuleResult result = new RuleResult("domain-layer-isolation");
        boolean found = false;

        for (File f : collectJavaFiles(root)) {
            if (!pathContains(f, "/domain/")) continue;
            found = true;
            String rel = relTo(f, root);
            String code = readText(f);

            Matcher importMatcher = IMPORT_LINE.matcher(code);
            String violation = null;
            while (importMatcher.find()) {
                String imported = importMatcher.group(1);
                if (FORBIDDEN_SEGMENT.matcher(imported).find()) {
                    violation = imported;
                    break;
                }
            }

            if (violation != null) {
                result.add(Finding.fail(rel,
                    "A domain/ class imports an upper layer — '" + violation
                        + "' (application/infrastructure/interfaces may reference domain, never the reverse, layer-architecture.md)"));
            } else {
                result.add(Finding.pass(rel + " (confirmed domain isolation)"));
            }
        }

        if (!found) result.add(Finding.skip("No Java files under domain/"));
        return result;
    }
}

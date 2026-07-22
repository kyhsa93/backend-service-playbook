package harness.rules;

import harness.Finding;
import harness.RuleResult;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static harness.JavaFiles.readText;
import static harness.JavaFiles.relTo;

/**
 * [32] Automatic ORM schema sync is forbidden in production config — fails if {@code
 * spring.jpa.hibernate.ddl-auto} is {@code update}/{@code create}/{@code create-drop}.
 * Schema changes must be managed only through Flyway/Liquibase migrations
 * (persistence.md) — automatic sync is permitted only in dev/test environments
 * (persistence.md, "the test environment is an exception").
 *
 * <p>Checks two files separately:
 * <ul>
 *   <li>The default (no-profile) {@code application.yml}/{@code application.yaml} — if
 *   {@code SPRING_PROFILES_ACTIVE} is missing in production, this value applies as-is, so
 *   it's dangerous if it's already set to {@code update}, etc.</li>
 *   <li>{@code application-prod.yml}/{@code application-prod.yaml} (or the {@code
 *   -production} variant) — the prod-profile-only override. If the {@code ddl-auto} key
 *   is simply absent, it inherits the default file's value as-is (already checked
 *   separately above), so it counts as PASS.</li>
 * </ul>
 *
 * <p>{@code src/test/resources} is excluded from this check — a Testcontainers test using
 * {@code create-drop} via {@code @DynamicPropertySource} is a legitimate exception that
 * persistence.md explicitly allows, and it's Java code (an annotation) rather than YAML in
 * the first place, so it isn't even something this rule parses.
 */
public final class NoOrmAutoSyncInProdConfig {
    private NoOrmAutoSyncInProdConfig() {
    }

    // build/ is an output directory into which Gradle copies src/main/resources as-is, so
    // without excluding it, the same file would also get picked up under build/resources
    // and scanned twice, with a risk of picking a stale copy depending on when the build
    // ran (excluded for the same reason as in JavaFiles.java).
    private static final Set<String> EXCLUDED_DIRS = Set.of("test", ".git", "build");
    private static final Set<String> DEFAULT_NAMES = Set.of("application.yml", "application.yaml");
    private static final Set<String> PROD_NAMES = Set.of(
        "application-prod.yml", "application-prod.yaml",
        "application-production.yml", "application-production.yaml");
    private static final Set<String> DISALLOWED_VALUES = Set.of("update", "create", "create-drop");
    private static final Pattern DDL_AUTO = Pattern.compile("ddl-auto:\\s*['\"]?([\\w-]+)['\"]?");

    public static RuleResult check(String rootPath) {
        File root = new File(rootPath);
        RuleResult result = new RuleResult("no-orm-autosync-in-prod-config");

        List<File> ymlFiles = new ArrayList<>();
        collectYmlFiles(root, ymlFiles);
        ymlFiles.sort(Comparator.comparing(File::getPath));

        File defaultFile = findFirst(ymlFiles, DEFAULT_NAMES);
        File prodFile = findFirst(ymlFiles, PROD_NAMES);

        if (defaultFile == null && prodFile == null) {
            result.add(Finding.skip("No application.yml / application-prod.yml"));
            return result;
        }

        if (defaultFile != null) checkFile(result, root, defaultFile, "default (no profile)");
        if (prodFile != null) checkFile(result, root, prodFile, "prod profile");

        return result;
    }

    private static void checkFile(RuleResult result, File root, File file, String profileLabel) {
        String rel = relTo(file, root);
        String content = readText(file);
        Matcher m = DDL_AUTO.matcher(content);

        if (!m.find()) {
            result.add(Finding.pass(rel + " (" + profileLabel + " — ddl-auto not set, no automatic schema sync)"));
            return;
        }

        String value = m.group(1);
        if (DISALLOWED_VALUES.contains(value)) {
            result.add(Finding.fail(rel,
                profileLabel + " spring.jpa.hibernate.ddl-auto: " + value
                    + " — automatic ORM schema sync is forbidden; only validate/none + Flyway/Liquibase migrations are allowed (persistence.md)"));
        } else {
            result.add(Finding.pass(rel + " (" + profileLabel + " — ddl-auto: " + value + ", an allowed value)"));
        }
    }

    private static File findFirst(List<File> files, Set<String> names) {
        for (File f : files) {
            if (names.contains(f.getName())) return f;
        }
        return null;
    }

    private static void collectYmlFiles(File dir, List<File> out) {
        if (!dir.exists()) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) {
                if (!EXCLUDED_DIRS.contains(child.getName())) {
                    collectYmlFiles(child, out);
                }
            } else {
                String name = child.getName();
                if (name.endsWith(".yml") || name.endsWith(".yaml")) out.add(child);
            }
        }
    }
}

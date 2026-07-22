package harness.rules;

import harness.Finding;
import harness.RuleResult;

import java.io.File;

import static harness.JavaFiles.collectJavaFiles;
import static harness.JavaFiles.pathContains;
import static harness.JavaFiles.readText;
import static harness.JavaFiles.relTo;

/**
 * [17] domain/ and application/ must not directly call {@code System.getenv(...)} —
 * environment-variable access must be wrapped in {@code @ConfigurationProperties} and done
 * only in config/ (or infrastructure/) (config.md — "config access lives in the
 * Infrastructure layer: @Value/@ConfigurationProperties injection targets are restricted
 * to Infrastructure's @Configuration/@Component classes").
 *
 * <p>{@code config/} is not a per-domain subpackage but a top-level shared package
 * (`com/example/accountservice/config/`), so it contains no "/domain/" or "/application/"
 * segment and is naturally excluded from this check — infrastructure/ passes for the same
 * reason (e.g. config/AwsProperties.java, config/SesProperties.java).
 */
public final class NoDirectEnvAccessOutsideConfig {
    private NoDirectEnvAccessOutsideConfig() {
    }

    private static final String FORBIDDEN_CALL = "System.getenv(";

    public static RuleResult check(String rootPath) {
        File root = new File(rootPath);
        RuleResult result = new RuleResult("no-direct-env-access-outside-config");
        boolean found = false;

        for (File f : collectJavaFiles(root)) {
            boolean inScope = pathContains(f, "/domain/") || pathContains(f, "/application/");
            if (!inScope) continue;
            found = true;
            String rel = relTo(f, root);
            String content = readText(f);

            if (content.contains(FORBIDDEN_CALL)) {
                result.add(Finding.fail(rel,
                    "Directly calling System.getenv() from domain/ or application/ is forbidden — access must go through @ConfigurationProperties in config/ only (config.md)"));
            } else {
                result.add(Finding.pass(rel + " (confirmed System.getenv is not used)"));
            }
        }

        if (!found) result.add(Finding.skip("No Java files under domain/ or application/"));
        return result;
    }
}

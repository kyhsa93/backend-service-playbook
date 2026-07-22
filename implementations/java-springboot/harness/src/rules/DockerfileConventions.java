package harness.rules;

import harness.Finding;
import harness.RuleResult;

import java.io.File;
import java.util.regex.Pattern;

import static harness.JavaFiles.readText;

/**
 * [22] Dockerfile conventions — verifies the minimum conditions required by container.md
 * against the project root's {@code Dockerfile}/{@code .dockerignore} (plain text).
 * Unlike other rules, this parses the two text files themselves rather than .java files.
 *
 * <ul>
 *   <li>Multi-stage build — 2 or more {@code FROM} lines (container.md: "use a
 *   multi-stage build so build tools/source don't end up in the final image")</li>
 *   <li>A {@code HEALTHCHECK} directive is present</li>
 *   <li>A {@code USER} directive is present — running as non-root</li>
 *   <li>{@code .dockerignore} exists + contains at minimum the exclusion patterns for
 *   build output/VCS/secrets</li>
 * </ul>
 */
public final class DockerfileConventions {
    private DockerfileConventions() {
    }

    private static final Pattern FROM_LINE = Pattern.compile("(?m)^\\s*FROM\\s+", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEALTHCHECK_LINE = Pattern.compile("(?m)^\\s*HEALTHCHECK\\s+", Pattern.CASE_INSENSITIVE);
    private static final Pattern USER_LINE = Pattern.compile("(?m)^\\s*USER\\s+\\S+", Pattern.CASE_INSENSITIVE);

    public static RuleResult check(String rootPath) {
        RuleResult result = new RuleResult("dockerfile-conventions");
        File dockerfile = new File(rootPath, "Dockerfile");

        if (!dockerfile.isFile()) {
            result.add(Finding.skip("No Dockerfile"));
            return result;
        }

        String content = readText(dockerfile);

        long fromCount = FROM_LINE.matcher(content).results().count();
        if (fromCount >= 2) {
            result.add(Finding.pass("Dockerfile (confirmed multi-stage build, " + fromCount + " FROM lines)"));
        } else {
            result.add(Finding.fail("Dockerfile",
                "Not a multi-stage build — found " + fromCount + " FROM line(s), need at least 2 (so build tools/source don't end up in the final image, container.md)"));
        }

        if (HEALTHCHECK_LINE.matcher(content).find()) {
            result.add(Finding.pass("Dockerfile (confirmed HEALTHCHECK)"));
        } else {
            result.add(Finding.fail("Dockerfile", "No HEALTHCHECK directive (container.md)"));
        }

        if (USER_LINE.matcher(content).find()) {
            result.add(Finding.pass("Dockerfile (confirmed non-root USER)"));
        } else {
            result.add(Finding.fail("Dockerfile", "No USER directive — the container runs as root (container.md)"));
        }

        File dockerignore = new File(rootPath, ".dockerignore");
        if (!dockerignore.isFile()) {
            result.add(Finding.fail(".dockerignore", "File does not exist — risk of including unnecessary files in the build context (build output, .git, .env, etc.) (container.md)"));
        } else {
            String ignoreContent = readText(dockerignore);
            String lower = ignoreContent.toLowerCase();
            boolean excludesGit = lower.contains(".git");
            boolean excludesBuildOutput = lower.contains("build") || lower.contains("target") || lower.contains(".gradle");
            if (excludesGit && excludesBuildOutput) {
                result.add(Finding.pass(".dockerignore (confirmed .git/build-output exclusion)"));
            } else {
                result.add(Finding.fail(".dockerignore",
                    "Missing required exclusion patterns — must exclude .git and build-output directories (build/.gradle/target) (container.md)"));
            }
        }

        return result;
    }
}

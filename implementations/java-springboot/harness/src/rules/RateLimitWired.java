package harness.rules;

import harness.Finding;
import harness.RuleResult;

import java.io.File;
import java.util.regex.Pattern;

import static harness.JavaFiles.collectJavaFiles;
import static harness.JavaFiles.nameWithoutExtension;
import static harness.JavaFiles.pathContains;
import static harness.JavaFiles.readText;
import static harness.JavaFiles.relTo;

/**
 * [28] Whether Rate Limiting is actually wired up — rate-limiting.md requires
 * double protection: a global {@code Filter} (per-method) plus a per-endpoint {@code
 * @RateLimiter} (Resilience4j annotation). This catches a "defined but never actually
 * applied" dead-code regression.
 *
 * <p>If {@code RateLimitFilter} hardcodes its limit values in a field, like {@code
 * RateLimiterConfig.custom().limitForPeriod(100)...}, they can't be adjusted at deploy
 * time via {@code application.yml}/environment variables — it must dynamically look up a
 * named instance (`http-write`/`http-read`) from {@code RateLimiterRegistry} instead. This
 * rule confirms three things:
 *
 * <ol>
 *   <li>Whether {@code RateLimitFilter} is registered as a Spring bean via {@code
 *   @Component} (Spring Boot auto-registers a Filter bean into the filter chain, applying
 *   it to the real request path — no separate registration file needed)</li>
 *   <li>Whether it avoids hardcoding limit values directly in a field via {@code
 *   RateLimiterConfig.custom(}</li>
 *   <li>Whether it injects {@code RateLimiterRegistry} and dynamically looks up a named
 *   instance via {@code .rateLimiter(...)}</li>
 * </ol>
 *
 * <p>It additionally checks whether an {@code @RateLimiter} annotation is actually
 * attached to an endpoint somewhere in {@code interfaces/} (REST Controller) — the
 * annotation itself is optional (per-endpoint granularity), so having none at all is not
 * flagged as a failure; "only the filter exists, the annotation approach isn't used yet"
 * is left as a SKIP.
 */
public final class RateLimitWired {
    private RateLimitWired() {
    }

    private static final Pattern COMPONENT_ANNOTATION = Pattern.compile("@Component\\b");
    private static final Pattern HARDCODED_CONFIG = Pattern.compile("RateLimiterConfig\\s*\\.\\s*custom\\s*\\(");
    private static final Pattern REGISTRY_FIELD = Pattern.compile("RateLimiterRegistry");
    private static final Pattern REGISTRY_LOOKUP = Pattern.compile("\\.\\s*rateLimiter\\s*\\(");
    private static final Pattern DISABLED_REGISTRATION =
        Pattern.compile("RateLimitFilter[\\s\\S]{0,200}?setEnabled\\s*\\(\\s*false\\s*\\)");
    private static final Pattern RATE_LIMITER_ANNOTATION_USE =
        Pattern.compile("@(?:io\\.github\\.resilience4j\\.ratelimiter\\.annotation\\.)?RateLimiter\\s*\\(\\s*name\\s*=");

    public static RuleResult check(String rootPath) {
        File root = new File(rootPath);
        RuleResult result = new RuleResult("rate-limit-wired");

        File filterFile = null;
        for (File f : collectJavaFiles(root)) {
            if (nameWithoutExtension(f).equals("RateLimitFilter")) {
                filterFile = f;
                break;
            }
        }

        if (filterFile == null) {
            result.add(Finding.skip("No common/web/RateLimitFilter.java (global Rate Limiting Filter)"));
            return result;
        }

        String rel = relTo(filterFile, root);
        String code = stripComments(readText(filterFile));

        if (!COMPONENT_ANNOTATION.matcher(code).find()) {
            result.add(Finding.fail(rel,
                "RateLimitFilter has no @Component — if it isn't registered as a Spring bean, it's dead code that never gets applied to the filter chain automatically (rate-limiting.md)"));
        } else {
            result.add(Finding.pass(rel + " (confirmed it's registered as a Spring bean via @Component)"));
        }

        if (HARDCODED_CONFIG.matcher(code).find()) {
            result.add(Finding.fail(rel,
                "Hardcodes limit values directly in a field via RateLimiterConfig.custom() — must look up a named instance from application.yml's RateLimiterRegistry instead, so it can be adjusted at deploy time (rate-limiting.md)"));
        } else if (REGISTRY_FIELD.matcher(code).find() && REGISTRY_LOOKUP.matcher(code).find()) {
            result.add(Finding.pass(rel + " (confirmed dynamic RateLimiterRegistry lookup — no hardcoding)"));
        } else {
            result.add(Finding.fail(rel,
                "Could not find code that looks up a named instance from RateLimiterRegistry — could not confirm whether limit values are adjustable at deploy time (rate-limiting.md)"));
        }

        boolean disabledSomewhere = false;
        for (File f : collectJavaFiles(root)) {
            if (!pathContains(f, "/config/") && !pathContains(f, "/infrastructure/")) continue;
            String content = readText(f);
            if (DISABLED_REGISTRATION.matcher(content).find()) {
                disabledSomewhere = true;
                result.add(Finding.fail(relTo(f, root),
                    "Disables RateLimitFilter via FilterRegistrationBean.setEnabled(false) — dead code that isn't actually applied to requests even though the filter is registered (rate-limiting.md)"));
            }
        }
        if (!disabledSomewhere) {
            result.add(Finding.pass("No configuration explicitly disables RateLimitFilter (automatic filter-chain application is preserved)"));
        }

        boolean annotationUsed = false;
        for (File f : collectJavaFiles(root)) {
            if (!pathContains(f, "/interfaces/")) continue;
            String content = stripComments(readText(f));
            if (RATE_LIMITER_ANNOTATION_USE.matcher(content).find()) {
                annotationUsed = true;
                result.add(Finding.pass(relTo(f, root) + " (confirmed per-endpoint granularity applied via the @RateLimiter annotation)"));
            }
        }
        if (!annotationUsed) {
            result.add(Finding.skip("No Controller under interfaces/ uses the @RateLimiter annotation (per-endpoint granularity) — this is not a failure since a global Filter alone is permitted by the principle"));
        }

        return result;
    }

    private static String stripComments(String content) {
        return content.replaceAll("/\\*[\\s\\S]*?\\*/", "").replaceAll("//.*", "");
    }
}

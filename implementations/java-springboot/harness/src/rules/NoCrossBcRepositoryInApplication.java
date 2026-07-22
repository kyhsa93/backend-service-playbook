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
 * [18] Direct Repository/Query references across BCs are forbidden — an application/ file
 * in one domain must not directly import another domain's domain/*Repository interface or
 * application/query/*Query interface. Cross-domain reads must go through an Adapter owned
 * by the calling side (an application/adapter/ interface + an infrastructure/
 * implementation, ACL) (cross-domain-communication.md; for this repository's actual
 * implementation see {@code AccountAdapter}/{@code PaymentCardAdapterImpl} etc. in
 * cross-domain.md).
 *
 * <p>Referencing your own domain's Repository/Query within the same domain is of course
 * allowed — the first path segment (e.g. "payment" in {@code
 * payment/application/command/CreatePaymentService.java}) is treated as the "owning
 * domain," and a violation is flagged only when the imported Repository/Query belongs to
 * a different domain.
 *
 * <p>An Adapter implementation (e.g. {@code
 * payment/infrastructure/PaymentAccountAdapterImpl.java}) lives under infrastructure/, so
 * it is not checked by this rule — the ACL pattern deliberately allows injecting another
 * domain's Query interface at that location (cross-domain.md).
 */
public final class NoCrossBcRepositoryInApplication {
    private NoCrossBcRepositoryInApplication() {
    }

    private static final Pattern DOMAIN_REPOSITORY_IMPORT =
        Pattern.compile("^import\\s+com\\.example\\.accountservice\\.(\\w+)\\.domain\\.(\\w*(?:Repository))\\s*;", Pattern.MULTILINE);
    private static final Pattern APPLICATION_QUERY_IMPORT =
        Pattern.compile("^import\\s+com\\.example\\.accountservice\\.(\\w+)\\.application\\.query\\.(\\w*(?:Query))\\s*;", Pattern.MULTILINE);

    public static RuleResult check(String rootPath) {
        File root = new File(rootPath);
        RuleResult result = new RuleResult("no-cross-bc-repository-in-application");
        boolean found = false;

        for (File f : collectJavaFiles(root)) {
            if (!pathContains(f, "/application/")) continue;
            String rel = relTo(f, root);
            String ownDomain = ownDomain(f);
            if (ownDomain == null) continue; // a file that doesn't follow the domain-path convention (<domain>/application/...)

            found = true;
            String code = readText(f);
            String violation = firstCrossDomainImport(code, ownDomain);

            if (violation != null) {
                result.add(Finding.fail(rel,
                    "An application/ file directly imports another domain's Repository/Query — '" + violation
                        + "' (owning domain: " + ownDomain + "). Cross-domain reads must go through an Adapter (ACL) (cross-domain-communication.md)"));
            } else {
                result.add(Finding.pass(rel + " (confirmed no direct cross-domain Repository/Query reference)"));
            }
        }

        if (!found) result.add(Finding.skip("No Java files under <domain>/application/"));
        return result;
    }

    /**
     * Treats the path segment immediately before "/application/" as the owning domain —
     * this correctly extracts "payment" from both the real repository layout ({@code
     * .../com/example/accountservice/payment/application/command/X.java}) and the fixture
     * layout ({@code payment/application/command/X.java}) (just looking at the rel path's
     * first segment would incorrectly yield "src" for the real layout).
     */
    private static String ownDomain(File file) {
        String path = file.getPath().replace(File.separatorChar, '/');
        int appIdx = path.indexOf("/application/");
        if (appIdx <= 0) return null;
        String before = path.substring(0, appIdx);
        int lastSlash = before.lastIndexOf('/');
        return lastSlash < 0 ? before : before.substring(lastSlash + 1);
    }

    private static String firstCrossDomainImport(String code, String ownDomain) {
        Matcher repoMatcher = DOMAIN_REPOSITORY_IMPORT.matcher(code);
        while (repoMatcher.find()) {
            String otherDomain = repoMatcher.group(1);
            if (!otherDomain.equals(ownDomain)) {
                return "com.example.accountservice." + otherDomain + ".domain." + repoMatcher.group(2);
            }
        }
        Matcher queryMatcher = APPLICATION_QUERY_IMPORT.matcher(code);
        while (queryMatcher.find()) {
            String otherDomain = queryMatcher.group(1);
            if (!otherDomain.equals(ownDomain)) {
                return "com.example.accountservice." + otherDomain + ".application.query." + queryMatcher.group(2);
            }
        }
        return null;
    }
}

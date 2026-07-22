package harness.rules;

import harness.Finding;
import harness.RuleResult;

import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static harness.JavaFiles.collectJavaFiles;
import static harness.JavaFiles.readText;
import static harness.JavaFiles.relTo;

/**
 * [31] Directly importing Domain across BCs is forbidden — the principle that other
 * Aggregates (whether within the same BC or a different one) may only be referenced by ID,
 * never by object reference (tactical-ddd.md "another Aggregate may only be referenced by
 * ID") applies just as much across BC boundaries. Fails if {@code <bc>/domain/*.java}
 * imports another BC's {@code <otherBc>/domain/*}.
 *
 * <p>Round 2's {@code domain-layer-isolation} (R1) only checks whether domain/ imports its
 * own upper layers (application/infrastructure/interfaces/), and {@code
 * no-cross-aggregate-reference} (R5) only looks between the two Aggregates Payment/Refund
 * within the payment BC — neither checks "directly importing another BC's domain/," so
 * there was a gap where, for example, {@code card/domain/*.java} importing {@code
 * payment/domain/Payment} would go uncaught. This rule closes that gap.
 *
 * <p>False-positive review: every domain/ file in this repository imports only {@code
 * common/IdGenerator} and never imports another BC's domain/ type (confirmed directly) —
 * any value needed across BCs is held only as an ID-string field, so no import is needed
 * at all. Importing your own domain/ type within the same BC (as distinct from {@code
 * Refund.java} importing {@code Payment}, e.g. {@code
 * payment/domain/RefundEligibilityService.java} importing {@code Payment}/{@code Refund}
 * from the same BC) is of course allowed, since otherBc == ownBc in that case.
 */
public final class NoCrossBcDomainImport {
    private NoCrossBcDomainImport() {
    }

    private static final Pattern DOMAIN_IMPORT =
        Pattern.compile("^import\\s+com\\.example\\.accountservice\\.(\\w+)\\.domain\\.(\\w+)\\s*;", Pattern.MULTILINE);

    public static RuleResult check(String rootPath) {
        File root = new File(rootPath);
        RuleResult result = new RuleResult("no-cross-bc-domain-import");
        boolean found = false;

        for (File f : collectJavaFiles(root)) {
            String ownBc = ownBc(f);
            if (ownBc == null) continue; // a file that doesn't follow the <bc>/domain/... path convention

            found = true;
            String rel = relTo(f, root);
            String code = readText(f);
            String violation = firstCrossBcImport(code, ownBc);

            if (violation != null) {
                result.add(Finding.fail(rel,
                    "A domain/ file directly imports another BC's domain/ type — '" + violation
                        + "' (owning BC: " + ownBc + "). Another Aggregate may only be referenced by ID, whether inside or across BCs — object references are forbidden (tactical-ddd.md)"));
            } else {
                result.add(Finding.pass(rel + " (confirmed no direct import of another BC's domain/)"));
            }
        }

        if (!found) result.add(Finding.skip("No Java files under <bc>/domain/"));
        return result;
    }

    /** Treats the path segment immediately before "/domain/" as the owning BC — works for both the real layout and the fixture layout. */
    private static String ownBc(File file) {
        String path = file.getPath().replace(File.separatorChar, '/');
        int domainIdx = path.indexOf("/domain/");
        if (domainIdx <= 0) return null;
        String before = path.substring(0, domainIdx);
        int lastSlash = before.lastIndexOf('/');
        return lastSlash < 0 ? before : before.substring(lastSlash + 1);
    }

    private static String firstCrossBcImport(String code, String ownBc) {
        Matcher m = DOMAIN_IMPORT.matcher(code);
        while (m.find()) {
            String otherBc = m.group(1);
            if (!otherBc.equals(ownBc)) {
                return "com.example.accountservice." + otherBc + ".domain." + m.group(2);
            }
        }
        return null;
    }
}

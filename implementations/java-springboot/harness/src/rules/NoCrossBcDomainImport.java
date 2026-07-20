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
 * [31] BC 간 Domain 직접 import 금지 — 다른 Aggregate(같은 BC 안이든 다른 BC든)는 ID 참조만
 * 허용하고 객체 참조는 금지한다는 원칙(tactical-ddd.md "다른 Aggregate는 ID 참조만 허용한다")은
 * BC 경계를 넘어서도 그대로 적용된다. {@code <bc>/domain/*.java}가 다른 BC의 {@code
 * <otherBc>/domain/*}를 import하면 실패.
 *
 * <p>round 2의 {@code domain-layer-isolation}(R1)은 domain/이 자신의 상위 레이어(application/
 * infrastructure/interfaces/)를 import하지 않는지만 보고, {@code no-cross-aggregate-reference}
 * (R5)는 payment BC 안의 Payment/Refund 두 Aggregate 사이만 본다 — 이 둘 다 "다른 BC의 domain/를
 * 직접 import"는 검사하지 않아서, 예를 들어 {@code card/domain/*.java}가 {@code
 * payment/domain/Payment}를 import해도 잡히지 않는 gap이 있었다. 이 규칙이 그 gap을 닫는다.
 *
 * <p>false-positive 검토: 이 저장소의 domain/ 파일들은 모두 {@code common/IdGenerator}만
 * import하고 다른 BC의 domain/ 타입은 import하지 않는다(직접 확인함) — 크로스 BC로 필요한 값은
 * ID 문자열 필드로만 들고 있어 import 자체가 필요 없다. 같은 BC 내부에서 자신의 domain/ 타입을
 * import하는 것(예: {@code Refund.java}가 {@code Payment}를 import하는 것과는 별개로,
 * {@code payment/domain/RefundEligibilityService.java}가 같은 BC의 {@code Payment}/{@code
 * Refund}를 import하는 것)은 otherBc == ownBc이므로 당연히 허용된다.
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
            if (ownBc == null) continue; // <bc>/domain/... 경로 규칙을 따르지 않는 파일

            found = true;
            String rel = relTo(f, root);
            String code = readText(f);
            String violation = firstCrossBcImport(code, ownBc);

            if (violation != null) {
                result.add(Finding.fail(rel,
                    "domain/ 파일이 다른 BC의 domain/ 타입을 직접 import — '" + violation
                        + "' (소속 BC: " + ownBc + "). 다른 Aggregate는 BC 안팎을 불문하고 ID 참조만 허용, 객체 참조 금지(tactical-ddd.md)"));
            } else {
                result.add(Finding.pass(rel + " (다른 BC의 domain/ 직접 import 없음 확인)"));
            }
        }

        if (!found) result.add(Finding.skip("<bc>/domain/ Java 파일 없음"));
        return result;
    }

    /** "/domain/" 바로 앞의 경로 세그먼트를 소속 BC로 본다 — 실제 레이아웃과 fixture 레이아웃 둘 다에서 동작. */
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

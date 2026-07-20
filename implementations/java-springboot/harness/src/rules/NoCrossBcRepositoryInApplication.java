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
 * [18] BC 간 Repository/Query 직접 참조 금지 — 한 도메인의 application/ 파일이 다른 도메인의
 * domain/*Repository 인터페이스나 application/query/*Query 인터페이스를 직접 import하면 안 된다.
 * 크로스 도메인 읽기는 반드시 호출하는 쪽이 소유한 Adapter(application/adapter/ 인터페이스 +
 * infrastructure/의 구현체, ACL)를 거쳐야 한다(cross-domain-communication.md, 이 저장소의 실제
 * 구현은 cross-domain.md의 {@code AccountAdapter}/{@code PaymentCardAdapterImpl} 등 참고).
 *
 * <p>같은 도메인 안에서 자신의 Repository/Query를 참조하는 것은 당연히 허용된다 — 파일 경로의 첫
 * 세그먼트(예: {@code payment/application/command/CreatePaymentService.java}의 "payment")를
 * "소속 도메인"으로 보고, import된 Repository/Query가 속한 도메인과 다를 때만 위반으로 잡는다.
 *
 * <p>Adapter 구현체(예: {@code payment/infrastructure/PaymentAccountAdapterImpl.java})는
 * infrastructure/에 있으므로 이 규칙의 검사 대상이 아니다 — ACL 패턴이 의도적으로 그 위치에서
 * 다른 도메인의 Query 인터페이스를 주입받는 것을 허용한다(cross-domain.md).
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
            if (ownDomain == null) continue; // 도메인 경로 규칙(<domain>/application/...)을 따르지 않는 파일

            found = true;
            String code = readText(f);
            String violation = firstCrossDomainImport(code, ownDomain);

            if (violation != null) {
                result.add(Finding.fail(rel,
                    "application/ 파일이 다른 도메인의 Repository/Query를 직접 import — '" + violation
                        + "' (소속 도메인: " + ownDomain + "). 크로스 도메인 읽기는 Adapter(ACL)를 거쳐야 함(cross-domain-communication.md)"));
            } else {
                result.add(Finding.pass(rel + " (도메인 간 Repository/Query 직접 참조 없음 확인)"));
            }
        }

        if (!found) result.add(Finding.skip("<domain>/application/ Java 파일 없음"));
        return result;
    }

    /**
     * "/application/" 바로 앞의 경로 세그먼트를 소속 도메인으로 본다 — 실제 저장소 레이아웃
     * ({@code .../com/example/accountservice/payment/application/command/X.java})과 fixture
     * 레이아웃({@code payment/application/command/X.java}) 둘 다에서 "payment"를 정확히 뽑아낸다
     * (rel 경로의 첫 세그먼트만 보면 실제 레이아웃에서 "src"가 나와 틀린다).
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

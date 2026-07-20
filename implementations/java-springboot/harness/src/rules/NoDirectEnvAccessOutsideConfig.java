package harness.rules;

import harness.Finding;
import harness.RuleResult;

import java.io.File;

import static harness.JavaFiles.collectJavaFiles;
import static harness.JavaFiles.pathContains;
import static harness.JavaFiles.readText;
import static harness.JavaFiles.relTo;

/**
 * [17] domain/·application/은 {@code System.getenv(...)}를 직접 호출할 수 없다 — 환경 변수 접근은
 * {@code @ConfigurationProperties}로 감싸 config/(또는 infrastructure/)에서만 한다(config.md —
 * "설정 접근은 Infrastructure 레이어: @Value/@ConfigurationProperties 주입 대상은 Infrastructure의
 * @Configuration/@Component 클래스로 한정한다").
 *
 * <p>{@code config/}는 도메인별 하위 패키지가 아니라 최상위 공용 패키지(`com/example/accountservice/config/`)라
 * "/domain/"·"/application/" 세그먼트를 포함하지 않으므로 자연히 검사 대상에서 제외된다 —
 * infrastructure/도 마찬가지로 통과한다(예: config/AwsProperties.java, config/SesProperties.java).
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
                    "domain/ 또는 application/에서 System.getenv() 직접 호출 금지 — @ConfigurationProperties로 config/에서만 접근해야 함(config.md)"));
            } else {
                result.add(Finding.pass(rel + " (System.getenv 미사용 확인)"));
            }
        }

        if (!found) result.add(Finding.skip("domain/ 또는 application/ Java 파일 없음"));
        return result;
    }
}

package harness.rules;

import harness.Finding;
import harness.RuleResult;

import java.io.File;
import java.util.regex.Pattern;

import static harness.JavaFiles.collectJavaFiles;
import static harness.JavaFiles.pathContains;
import static harness.JavaFiles.readText;
import static harness.JavaFiles.relTo;

/**
 * [19] domain/ 레이어에서 로깅 금지 — {@code org.slf4j.*}, Lombok {@code @Slf4j}, 직접
 * {@code LoggerFactory.getLogger(...)} 호출 어느 것도 domain/ 안에서 쓸 수 없다
 * (observability.md — "Domain 레이어에서 로깅 금지: Account가 이 원칙을 준수한다 — 신규
 * 도메인 메서드 추가 시에도 유지한다").
 */
public final class NoLoggingInDomain {
    private NoLoggingInDomain() {
    }

    private static final Pattern LOGGING_USAGE = Pattern.compile("org\\.slf4j|@Slf4j\\b|LoggerFactory");

    public static RuleResult check(String rootPath) {
        File root = new File(rootPath);
        RuleResult result = new RuleResult("no-logging-in-domain");
        boolean found = false;

        for (File f : collectJavaFiles(root)) {
            if (!pathContains(f, "/domain/")) continue;
            found = true;
            String rel = relTo(f, root);
            String content = readText(f);

            if (LOGGING_USAGE.matcher(content).find()) {
                result.add(Finding.fail(rel,
                    "domain/ 클래스에서 로깅 사용 금지 — slf4j/@Slf4j/LoggerFactory 어느 것도 쓸 수 없음(observability.md)"));
            } else {
                result.add(Finding.pass(rel + " (로깅 미사용 확인)"));
            }
        }

        if (!found) result.add(Finding.skip("domain/ Java 파일 없음"));
        return result;
    }
}

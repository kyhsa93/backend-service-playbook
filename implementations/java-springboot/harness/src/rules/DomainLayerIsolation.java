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
 * [13] Domain 레이어 격리 — {@code <domain>/domain/} 안의 파일은 자기 자신의
 * {@code application/}/{@code infrastructure/}/{@code interfaces/}는 물론, 형제 도메인의
 * 해당 레이어도 import할 수 없다(layer-architecture.md — "상위 레이어는 하위 레이어에 의존할 수
 * 있지만 역방향은 금지된다").
 *
 * <p>경로(패키지) 기반 검사다 — {@code domain-purity} 규칙이 특정 Spring 어노테이션
 * (`@Service`/`@Component`/...) 문자열만 블록리스트로 잡는 것과 달리, 이 규칙은 어떤 프레임워크
 * 이름도 하드코딩하지 않고 "import 문의 패키지 경로에 application/infrastructure/interfaces
 * 세그먼트가 있는가"만 본다 — 그래서 Spring이 아닌 임의의 상위 레이어 클래스를 domain이 참조하는
 * 것도 함께 잡아낸다(더 구조적/포괄적인 검사).
 */
public final class DomainLayerIsolation {
    private DomainLayerIsolation() {
    }

    private static final Pattern IMPORT_LINE = Pattern.compile("^import\\s+(?:static\\s+)?([\\w.]+);", Pattern.MULTILINE);
    private static final Pattern FORBIDDEN_SEGMENT = Pattern.compile("\\.(application|infrastructure|interfaces)\\.");

    public static RuleResult check(String rootPath) {
        File root = new File(rootPath);
        RuleResult result = new RuleResult("domain-layer-isolation");
        boolean found = false;

        for (File f : collectJavaFiles(root)) {
            if (!pathContains(f, "/domain/")) continue;
            found = true;
            String rel = relTo(f, root);
            String code = readText(f);

            Matcher importMatcher = IMPORT_LINE.matcher(code);
            String violation = null;
            while (importMatcher.find()) {
                String imported = importMatcher.group(1);
                if (FORBIDDEN_SEGMENT.matcher(imported).find()) {
                    violation = imported;
                    break;
                }
            }

            if (violation != null) {
                result.add(Finding.fail(rel,
                    "domain/ 클래스가 상위 레이어 import — '" + violation
                        + "' (application/infrastructure/interfaces는 domain을 참조할 수만 있고 그 역은 금지, layer-architecture.md)"));
            } else {
                result.add(Finding.pass(rel + " (domain 격리 확인)"));
            }
        }

        if (!found) result.add(Finding.skip("domain/ Java 파일 없음"));
        return result;
    }
}

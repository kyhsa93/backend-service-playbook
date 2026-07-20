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
 * [14] Interfaces 레이어(REST Controller 등)는 infrastructure/를 직접 import할 수 없다 —
 * Application(`application/command`/`application/query`의 Command/Query Service)만 거쳐야 한다
 * (layer-architecture.md — {@code interfaces/rest -> application -> domain}, infrastructure는
 * domain을 구현할 뿐 interfaces가 직접 알아서는 안 된다).
 *
 * <p>자기 도메인/형제 도메인 구분 없이 어떤 {@code infrastructure/} 세그먼트든 import 경로에
 * 있으면 위반으로 잡는다 — domain-layer-isolation과 동일한 경로 기반 접근.
 */
public final class InterfaceNoInfrastructure {
    private InterfaceNoInfrastructure() {
    }

    private static final Pattern IMPORT_LINE = Pattern.compile("^import\\s+(?:static\\s+)?([\\w.]+);", Pattern.MULTILINE);
    private static final Pattern INFRASTRUCTURE_SEGMENT = Pattern.compile("\\.infrastructure\\.");

    public static RuleResult check(String rootPath) {
        File root = new File(rootPath);
        RuleResult result = new RuleResult("interface-no-infrastructure");
        boolean found = false;

        for (File f : collectJavaFiles(root)) {
            if (!pathContains(f, "/interfaces/")) continue;
            found = true;
            String rel = relTo(f, root);
            String code = readText(f);

            Matcher importMatcher = IMPORT_LINE.matcher(code);
            String violation = null;
            while (importMatcher.find()) {
                String imported = importMatcher.group(1);
                if (INFRASTRUCTURE_SEGMENT.matcher(imported).find()) {
                    violation = imported;
                    break;
                }
            }

            if (violation != null) {
                result.add(Finding.fail(rel,
                    "interfaces/ 클래스가 infrastructure/ 직접 import — '" + violation
                        + "' (interfaces -> application -> domain 순서를 지켜야 함, infrastructure는 application을 거쳐야 함, layer-architecture.md)"));
            } else {
                result.add(Finding.pass(rel + " (infrastructure 미참조 확인)"));
            }
        }

        if (!found) result.add(Finding.skip("interfaces/ Java 파일 없음"));
        return result;
    }
}

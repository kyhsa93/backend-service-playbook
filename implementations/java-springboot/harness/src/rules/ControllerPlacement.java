package harness.rules;

import harness.Finding;
import harness.RuleResult;

import java.io.File;
import java.util.regex.Pattern;

import static harness.JavaFiles.collectJavaFiles;
import static harness.JavaFiles.pathContains;
import static harness.JavaFiles.readText;
import static harness.JavaFiles.relTo;

/** [5] @RestController — interfaces/rest/ 에만 허용 */
public final class ControllerPlacement {
    private ControllerPlacement() {
    }

    // "@RestControllerAdvice"(전역 예외 처리, common/web/ 배치가 정석 — shared-modules.md 참고)는
    // "@RestController"의 접두 문자열이라 단순 contains로는 오탐한다. Advice가 아닌 실제
    // @RestController 사용만 이 규칙의 대상으로 삼는다.
    private static final Pattern REST_CONTROLLER = Pattern.compile("@RestController(?!Advice)");

    public static RuleResult check(String rootPath) {
        File root = new File(rootPath);
        RuleResult result = new RuleResult("controller-placement");
        boolean found = false;
        for (File f : collectJavaFiles(root)) {
            String content = readText(f);
            if (!REST_CONTROLLER.matcher(content).find()) continue;
            found = true;
            String rel = relTo(f, root);
            if (pathContains(f, "/interfaces/")) {
                result.add(Finding.pass(rel + " (@RestController)"));
            } else {
                result.add(Finding.fail(rel, "@RestController는 interfaces/ 패키지 안에 있어야 함"));
            }
        }
        if (!found) result.add(Finding.skip("@RestController 없음"));
        return result;
    }
}

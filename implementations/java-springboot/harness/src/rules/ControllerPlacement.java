package harness.rules;

import harness.Finding;
import harness.RuleResult;

import java.io.File;

import static harness.JavaFiles.collectJavaFiles;
import static harness.JavaFiles.pathContains;
import static harness.JavaFiles.readText;
import static harness.JavaFiles.relTo;

/** [5] @RestController — interfaces/rest/ 에만 허용 */
public final class ControllerPlacement {
    private ControllerPlacement() {
    }

    public static RuleResult check(String rootPath) {
        File root = new File(rootPath);
        RuleResult result = new RuleResult("controller-placement");
        boolean found = false;
        for (File f : collectJavaFiles(root)) {
            String content = readText(f);
            if (!content.contains("@RestController")) continue;
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

package harness.rules;

import harness.Finding;
import harness.RuleResult;

import java.io.File;
import java.util.regex.Pattern;

import static harness.JavaFiles.collectJavaFiles;
import static harness.JavaFiles.pathContains;
import static harness.JavaFiles.readText;
import static harness.JavaFiles.relTo;

/** [5] @RestController — only allowed under interfaces/rest/ */
public final class ControllerPlacement {
    private ControllerPlacement() {
    }

    // "@RestControllerAdvice" (global exception handling — canonically placed under
    // common/web/, see shared-modules.md) starts with the string "@RestController", so a
    // naive contains() check would false-positive on it. This rule targets only actual
    // @RestController usage, not Advice.
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
                result.add(Finding.fail(rel, "@RestController must be inside the interfaces/ package"));
            }
        }
        if (!found) result.add(Finding.skip("No @RestController"));
        return result;
    }
}

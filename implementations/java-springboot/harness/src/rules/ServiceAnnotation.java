package harness.rules;

import harness.Finding;
import harness.RuleResult;

import java.io.File;

import static harness.JavaFiles.collectJavaFiles;
import static harness.JavaFiles.pathContains;
import static harness.JavaFiles.readText;
import static harness.JavaFiles.relTo;

/** [3] @Service — application/ 에만 허용 */
public final class ServiceAnnotation {
    private ServiceAnnotation() {
    }

    public static RuleResult check(String rootPath) {
        File root = new File(rootPath);
        RuleResult result = new RuleResult("service-annotation");
        boolean found = false;
        for (File f : collectJavaFiles(root)) {
            String content = readText(f);
            if (!content.contains("@Service")) continue;
            found = true;
            String rel = relTo(f, root);
            if (pathContains(f, "/application/")) {
                result.add(Finding.pass(rel + " (@Service)"));
            } else {
                result.add(Finding.fail(rel, "@Service는 application/ 패키지 안에 있어야 함"));
            }
        }
        if (!found) result.add(Finding.skip("@Service 없음"));
        return result;
    }
}

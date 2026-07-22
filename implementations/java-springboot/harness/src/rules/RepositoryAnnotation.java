package harness.rules;

import harness.Finding;
import harness.RuleResult;

import java.io.File;

import static harness.JavaFiles.collectJavaFiles;
import static harness.JavaFiles.pathContains;
import static harness.JavaFiles.readText;
import static harness.JavaFiles.relTo;

/** [2] @Repository — only allowed under infrastructure/ */
public final class RepositoryAnnotation {
    private RepositoryAnnotation() {
    }

    public static RuleResult check(String rootPath) {
        File root = new File(rootPath);
        RuleResult result = new RuleResult("repository-annotation");
        boolean found = false;
        for (File f : collectJavaFiles(root)) {
            String content = readText(f);
            if (!content.contains("@Repository")) continue;
            found = true;
            String rel = relTo(f, root);
            if (pathContains(f, "/infrastructure/")) {
                result.add(Finding.pass(rel + " (@Repository)"));
            } else {
                result.add(Finding.fail(rel, "@Repository must be inside the infrastructure/ package"));
            }
        }
        if (!found) result.add(Finding.skip("No @Repository"));
        return result;
    }
}

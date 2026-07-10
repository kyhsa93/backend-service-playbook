package harness.rules;

import harness.Finding;
import harness.RuleResult;

import java.io.File;
import java.util.List;
import java.util.regex.Pattern;

import static harness.JavaFiles.collectJavaFiles;
import static harness.JavaFiles.nameWithoutExtension;
import static harness.JavaFiles.relTo;

/** [1] 파일명 PascalCase 검사 */
public final class FileNaming {
    private FileNaming() {
    }

    private static final Pattern PASCAL_CASE = Pattern.compile("^[A-Z][A-Za-z0-9]*$");

    public static RuleResult check(String rootPath) {
        File root = new File(rootPath);
        RuleResult result = new RuleResult("file-naming");
        List<File> files = collectJavaFiles(root);
        if (files.isEmpty()) {
            result.add(Finding.skip("Java 파일 없음"));
            return result;
        }
        for (File f : files) {
            String rel = relTo(f, root);
            if (PASCAL_CASE.matcher(nameWithoutExtension(f)).matches()) {
                result.add(Finding.pass(rel));
            } else {
                result.add(Finding.fail(rel, "클래스명은 PascalCase 여야 함"));
            }
        }
        return result;
    }
}

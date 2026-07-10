package harness.rules;

import harness.Finding;
import harness.RuleResult;

import java.io.File;
import java.util.regex.Pattern;

import static harness.JavaFiles.collectJavaFiles;
import static harness.JavaFiles.pathContains;
import static harness.JavaFiles.readText;
import static harness.JavaFiles.relTo;

/** [4] domain/ 에 Spring 어노테이션 금지 */
public final class DomainPurity {
    private DomainPurity() {
    }

    private static final Pattern FORBIDDEN_ANNOTATIONS =
        Pattern.compile("@Service|@Component|@Repository|@Controller|@RestController");

    public static RuleResult check(String rootPath) {
        File root = new File(rootPath);
        RuleResult result = new RuleResult("domain-purity");
        boolean found = false;
        for (File f : collectJavaFiles(root)) {
            if (!pathContains(f, "/domain/")) continue;
            found = true;
            String rel = relTo(f, root);
            String content = readText(f);
            if (FORBIDDEN_ANNOTATIONS.matcher(content).find()) {
                result.add(Finding.fail(rel, "domain/ 클래스에 Spring 어노테이션 사용 금지"));
            } else {
                result.add(Finding.pass(rel + " (domain 순수성)"));
            }
        }
        if (!found) result.add(Finding.skip("domain/ Java 파일 없음"));
        return result;
    }
}

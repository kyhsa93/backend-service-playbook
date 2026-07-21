package harness.rules;

import harness.Finding;
import harness.RuleResult;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import static harness.JavaFiles.relTo;

/** [6] 패키지 구조 검사 (4레이어 + CQRS) */
public final class PackageStructure {
    private PackageStructure() {
    }

    // build/는 컴파일된 .class 파일이 패키지 구조를 그대로 미러링하므로, 제외하지 않으면
    // 실제 소스와 build/classes 양쪽에서 같은 도메인이 중복으로 잡힌다(JavaFiles.java와
    // 동일한 이유로 제외).
    private static final Set<String> EXCLUDED_DIRS = Set.of("test", ".git", "build");

    public static RuleResult check(String rootPath) {
        File root = new File(rootPath);
        RuleResult result = new RuleResult("package-structure");

        List<File> domainDirs = new ArrayList<>();
        findDomainDirs(root, domainDirs);
        domainDirs.sort(Comparator.comparing(File::getPath));

        if (domainDirs.isEmpty()) {
            result.add(Finding.skip("domain/ 디렉토리 없음"));
            return result;
        }

        for (File domainDir : domainDirs) {
            File parent = domainDir.getParentFile();
            String relParent = relTo(parent, root);
            for (String layer : List.of("application", "infrastructure", "interfaces")) {
                File dir = new File(parent, layer);
                if (dir.isDirectory()) {
                    result.add(Finding.pass(relParent + "/" + layer + "/"));
                } else {
                    result.add(Finding.fail(relParent + "/" + layer + "/", "디렉토리 없음"));
                }
            }
            for (String sub : List.of("command", "query")) {
                File dir = new File(parent, "application/" + sub);
                if (dir.isDirectory()) {
                    result.add(Finding.pass(relParent + "/application/" + sub + "/"));
                } else {
                    result.add(Finding.fail(relParent + "/application/" + sub + "/", "CQRS 디렉토리 없음"));
                }
            }
        }
        return result;
    }

    private static void findDomainDirs(File dir, List<File> out) {
        if (!dir.exists()) return;
        if (dir.isDirectory() && dir.getName().equals("domain")) {
            out.add(dir);
        }
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory() && !EXCLUDED_DIRS.contains(child.getName())) {
                findDomainDirs(child, out);
            }
        }
    }
}

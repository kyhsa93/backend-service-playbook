package harness.rules;

import harness.Finding;
import harness.RuleResult;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import static harness.JavaFiles.relTo;

/** [6] Package structure check (4 layers + CQRS) */
public final class PackageStructure {
    private PackageStructure() {
    }

    // build/ mirrors the package structure of the compiled .class files, so without
    // excluding it the same domain would get picked up twice — once from real source and
    // once from build/classes (excluded for the same reason as in JavaFiles.java).
    private static final Set<String> EXCLUDED_DIRS = Set.of("test", ".git", "build");

    public static RuleResult check(String rootPath) {
        File root = new File(rootPath);
        RuleResult result = new RuleResult("package-structure");

        List<File> domainDirs = new ArrayList<>();
        findDomainDirs(root, domainDirs);
        domainDirs.sort(Comparator.comparing(File::getPath));

        if (domainDirs.isEmpty()) {
            result.add(Finding.skip("No domain/ directory"));
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
                    result.add(Finding.fail(relParent + "/" + layer + "/", "Directory does not exist"));
                }
            }
            for (String sub : List.of("command", "query")) {
                File dir = new File(parent, "application/" + sub);
                if (dir.isDirectory()) {
                    result.add(Finding.pass(relParent + "/application/" + sub + "/"));
                } else {
                    result.add(Finding.fail(relParent + "/application/" + sub + "/", "CQRS directory does not exist"));
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

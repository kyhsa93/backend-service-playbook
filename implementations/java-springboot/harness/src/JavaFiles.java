package harness;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Finds every .java file under root — the test/, .git/, and build/ directories are excluded
 * entirely (at any depth). This matches the original bash version's behavior (which excluded
 * test/.git paths via find's -not -path). build/ is excluded because it's a cache/output
 * directory produced by Gradle/Spotless; depending on when the build ran, it may contain an
 * incomplete tree mirroring only some files, and scanning it as if it were real source can
 * produce false positives (e.g. a directory-structure rule wrongly flagging "the layer
 * directory is missing").
 */
public final class JavaFiles {
    private JavaFiles() {
    }

    private static final Set<String> EXCLUDED_DIRS = Set.of("test", ".git", "build");

    public static List<File> collectJavaFiles(File root) {
        List<File> result = new ArrayList<>();
        walk(root, result);
        result.sort(Comparator.comparing(File::getPath));
        return result;
    }

    private static void walk(File dir, List<File> out) {
        if (!dir.exists()) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) {
                if (!EXCLUDED_DIRS.contains(child.getName())) {
                    walk(child, out);
                }
            } else if (child.getName().endsWith(".java")) {
                out.add(child);
            }
        }
    }

    public static String nameWithoutExtension(File file) {
        String name = file.getName();
        return name.substring(0, name.length() - ".java".length());
    }

    public static String relTo(File file, File root) {
        return root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/');
    }

    public static boolean pathContains(File file, String segment) {
        return file.getPath().replace(File.separatorChar, '/').contains(segment);
    }

    public static String readText(File file) {
        try {
            return Files.readString(file.toPath());
        } catch (Exception e) {
            return "";
        }
    }
}

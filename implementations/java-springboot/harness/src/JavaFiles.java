package harness;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * root 아래 모든 .java 파일을 찾는다 — test/, .git/, build/ 디렉토리는(깊이 무관하게) 통째로
 * 제외한다. 원본 bash 버전(find로 test/.git 경로를 -not -path로 제외하던 것)과 동일한 동작.
 * build/는 Gradle/Spotless가 만드는 캐시·산출물 디렉토리라, 빌드 시점에 따라 일부 파일만
 * 미러링된 불완전한 트리를 실제 소스처럼 스캔해 오탐(예: 디렉토리-구조 규칙이 "레이어 디렉토리가
 * 없다"고 잘못 잡는 것)을 낼 수 있어 제외한다.
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

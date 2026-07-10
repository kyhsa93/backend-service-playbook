package harness

import java.io.File

private val EXCLUDED_DIRS = setOf("test", ".git", "build")

/**
 * root 아래 모든 .kt 파일을 찾는다 — test/, .git/, build/ 디렉토리는(깊이 무관하게)
 * 통째로 제외한다. 원본 bash 버전(find로 test/.git/build 경로를 -not -path로 제외하던 것)과 동일한 동작.
 */
fun collectKtFiles(root: File): List<File> {
    if (!root.exists()) return emptyList()
    return root.walkTopDown()
        .onEnter { dir -> dir.name !in EXCLUDED_DIRS }
        .filter { it.isFile && it.extension == "kt" }
        .sortedBy { it.path }
        .toList()
}

/** root 기준 상대경로를 슬래시(`/`) 구분자로 정규화해서 돌려준다. */
fun File.relTo(root: File): String =
    this.relativeTo(root).path.replace(File.separatorChar, '/')

/** 경로 문자열이 슬래시로 정규화된 형태에서 특정 세그먼트(예: "/domain/")를 포함하는지. */
fun File.pathContains(segment: String): Boolean =
    this.path.replace(File.separatorChar, '/').contains(segment)

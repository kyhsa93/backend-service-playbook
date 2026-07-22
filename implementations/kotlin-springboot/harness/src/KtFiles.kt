package harness

import java.io.File

private val EXCLUDED_DIRS = setOf("test", ".git", "build")

/**
 * Finds every .kt file under root — the test/, .git/, build/ directories are excluded
 * entirely (regardless of depth). Behaves the same as the original bash version (which excluded
 * test/.git/build paths via find's -not -path).
 */
fun collectKtFiles(root: File): List<File> {
    if (!root.exists()) return emptyList()
    return root.walkTopDown()
        .onEnter { dir -> dir.name !in EXCLUDED_DIRS }
        .filter { it.isFile && it.extension == "kt" }
        .sortedBy { it.path }
        .toList()
}

/** Returns the path relative to root, normalized with slash(`/`) separators. */
fun File.relTo(root: File): String =
    this.relativeTo(root).path.replace(File.separatorChar, '/')

/** Whether the path string, once normalized with slashes, contains a specific segment (e.g. "/domain/"). */
fun File.pathContains(segment: String): Boolean =
    this.path.replace(File.separatorChar, '/').contains(segment)

/**
 * Returns the segment immediately preceding a given layer directory name (e.g. "application") in the
 * path — used to determine "which domain/BC does this file belong to" from the path alone, in this
 * repository's `<domain>/{domain,application,infrastructure,interfaces}/` structure. Returns null if
 * there is no layer segment (or if it's the first segment).
 */
fun File.segmentBefore(layer: String): String? {
    val parts = this.path.replace(File.separatorChar, '/').split('/')
    val idx = parts.indexOf(layer)
    return if (idx > 0) parts[idx - 1] else null
}

package harness.rules

import harness.*
import java.io.File

private val PASCAL_CASE = Regex("^[A-Z][A-Za-z0-9]*$")

/** [1] 파일명 PascalCase 검사 */
fun checkFileNaming(rootPath: String): RuleResult {
    val root = File(rootPath)
    val result = RuleResult("file-naming")
    val files = collectKtFiles(root)
    if (files.isEmpty()) {
        result.add(skipFinding("Kotlin 파일 없음"))
        return result
    }
    for (f in files) {
        val rel = f.relTo(root)
        if (PASCAL_CASE.matches(f.nameWithoutExtension)) {
            result.add(passFinding(rel))
        } else {
            result.add(failFinding(rel, "파일명은 PascalCase.kt 여야 함"))
        }
    }
    return result
}

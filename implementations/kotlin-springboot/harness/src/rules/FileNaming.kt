package harness.rules

import harness.*
import java.io.File

private val PASCAL_CASE = Regex("^[A-Z][A-Za-z0-9]*$")

/** [1] Checks that file names are PascalCase */
fun checkFileNaming(rootPath: String): RuleResult {
    val root = File(rootPath)
    val result = RuleResult("file-naming")
    val files = collectKtFiles(root)
    if (files.isEmpty()) {
        result.add(skipFinding("no Kotlin files"))
        return result
    }
    for (f in files) {
        val rel = f.relTo(root)
        if (PASCAL_CASE.matches(f.nameWithoutExtension)) {
            result.add(passFinding(rel))
        } else {
            result.add(failFinding(rel, "file name must be PascalCase.kt"))
        }
    }
    return result
}

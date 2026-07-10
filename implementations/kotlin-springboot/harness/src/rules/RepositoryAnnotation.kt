package harness.rules

import harness.*
import java.io.File

/** [2] @Repository — infrastructure/ 에만 허용 */
fun checkRepositoryAnnotation(rootPath: String): RuleResult {
    val root = File(rootPath)
    val result = RuleResult("repository-annotation")
    var found = false
    for (f in collectKtFiles(root)) {
        val content = f.readText()
        if (!content.contains("@Repository")) continue
        found = true
        val rel = f.relTo(root)
        if (f.pathContains("/infrastructure/")) {
            result.add(passFinding("$rel (@Repository)"))
        } else {
            result.add(failFinding(rel, "@Repository는 infrastructure/ 패키지 안에 있어야 함"))
        }
    }
    if (!found) result.add(skipFinding("@Repository 없음"))
    return result
}

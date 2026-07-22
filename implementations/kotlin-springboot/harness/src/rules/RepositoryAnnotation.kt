package harness.rules

import harness.*
import java.io.File

/** [2] @Repository — allowed only in infrastructure/ */
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
            result.add(failFinding(rel, "@Repository must be inside the infrastructure/ package"))
        }
    }
    if (!found) result.add(skipFinding("no @Repository"))
    return result
}

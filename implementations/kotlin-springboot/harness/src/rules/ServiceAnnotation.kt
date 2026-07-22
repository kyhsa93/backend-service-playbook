package harness.rules

import harness.*
import java.io.File

/** [3] @Service — allowed only in application/ */
fun checkServiceAnnotation(rootPath: String): RuleResult {
    val root = File(rootPath)
    val result = RuleResult("service-annotation")
    var found = false
    for (f in collectKtFiles(root)) {
        val content = f.readText()
        if (!content.contains("@Service")) continue
        found = true
        val rel = f.relTo(root)
        if (f.pathContains("/application/")) {
            result.add(passFinding("$rel (@Service)"))
        } else {
            result.add(failFinding(rel, "@Service must be inside the application/ package"))
        }
    }
    if (!found) result.add(skipFinding("no @Service"))
    return result
}

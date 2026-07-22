package harness.rules

import harness.*
import java.io.File

private val REST_CONTROLLER = Regex("@RestController\\b")

/** [5] @RestController — allowed only in interfaces/ */
fun checkControllerPlacement(rootPath: String): RuleResult {
    val root = File(rootPath)
    val result = RuleResult("controller-placement")
    var found = false
    for (f in collectKtFiles(root)) {
        val content = f.readText()
        if (!REST_CONTROLLER.containsMatchIn(content)) continue
        found = true
        val rel = f.relTo(root)
        if (f.pathContains("/interfaces/")) {
            result.add(passFinding("$rel (@RestController)"))
        } else {
            result.add(failFinding(rel, "@RestController must be inside the interfaces/ package"))
        }
    }
    if (!found) result.add(skipFinding("no @RestController"))
    return result
}

package harness.rules

import harness.*
import java.io.File

/** [5] @RestController — interfaces/ 에만 허용 */
fun checkControllerPlacement(rootPath: String): RuleResult {
    val root = File(rootPath)
    val result = RuleResult("controller-placement")
    var found = false
    for (f in collectKtFiles(root)) {
        val content = f.readText()
        if (!content.contains("@RestController")) continue
        found = true
        val rel = f.relTo(root)
        if (f.pathContains("/interfaces/")) {
            result.add(passFinding("$rel (@RestController)"))
        } else {
            result.add(failFinding(rel, "@RestController는 interfaces/ 패키지 안에 있어야 함"))
        }
    }
    if (!found) result.add(skipFinding("@RestController 없음"))
    return result
}

package harness.rules

import harness.*
import java.io.File

private val SEALED_EXCEPTION = Regex("(?m)^\\s*(public |internal |private )?sealed class.*(Exception|Error)")

/** [6] sealed class 에러 — domain/ 에 위치 */
fun checkSealedException(rootPath: String): RuleResult {
    val root = File(rootPath)
    val result = RuleResult("sealed-exception")
    var found = false
    for (f in collectKtFiles(root)) {
        val content = f.readText()
        if (!SEALED_EXCEPTION.containsMatchIn(content)) continue
        found = true
        val rel = f.relTo(root)
        if (f.pathContains("/domain/")) {
            result.add(passFinding("$rel (sealed exception)"))
        } else {
            result.add(failFinding(rel, "sealed 예외 계층은 domain/ 안에 있어야 함"))
        }
    }
    if (!found) result.add(skipFinding("sealed 예외 없음"))
    return result
}

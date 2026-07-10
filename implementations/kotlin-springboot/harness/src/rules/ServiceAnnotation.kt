package harness.rules

import harness.*
import java.io.File

/** [3] @Service — application/ 에만 허용 */
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
            result.add(failFinding(rel, "@Service는 application/ 패키지 안에 있어야 함"))
        }
    }
    if (!found) result.add(skipFinding("@Service 없음"))
    return result
}

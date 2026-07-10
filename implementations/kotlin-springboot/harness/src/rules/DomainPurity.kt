package harness.rules

import harness.*
import java.io.File

private val FORBIDDEN_ANNOTATIONS = Regex("@Service|@Component|@Repository|@Controller|@RestController")

/** [4] domain/ 순수성 — Spring 어노테이션 금지 */
fun checkDomainPurity(rootPath: String): RuleResult {
    val root = File(rootPath)
    val result = RuleResult("domain-purity")
    var found = false
    for (f in collectKtFiles(root)) {
        if (!f.pathContains("/domain/")) continue
        found = true
        val rel = f.relTo(root)
        val content = f.readText()
        if (FORBIDDEN_ANNOTATIONS.containsMatchIn(content)) {
            result.add(failFinding(rel, "domain/ 클래스에 Spring 어노테이션 사용 금지"))
        } else {
            result.add(passFinding("$rel (domain 순수성)"))
        }
    }
    if (!found) result.add(skipFinding("domain/ Kotlin 파일 없음"))
    return result
}

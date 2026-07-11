package harness.rules

import harness.*
import java.io.File

private val FORBIDDEN_ANNOTATIONS = Regex("@Service|@Component|@Repository|@Controller|@RestController")

// domain/ 은 어떤 프레임워크/ORM에도 의존하지 않는다. JPA 애노테이션(@Entity/@Embeddable 등)은
// jakarta.persistence import 없이는 쓸 수 없으므로, 이 import를 회귀 신호로 삼는다.
// (애노테이션 문자열만 매칭하면 doc 주석의 언급까지 오탐할 수 있어 import를 기준으로 검사한다.)
private val FORBIDDEN_PERSISTENCE_IMPORT = Regex("""import\s+jakarta\.persistence""")

/** [4] domain/ 순수성 — Spring/JPA 등 프레임워크 의존 금지 */
fun checkDomainPurity(rootPath: String): RuleResult {
    val root = File(rootPath)
    val result = RuleResult("domain-purity")
    var found = false
    for (f in collectKtFiles(root)) {
        if (!f.pathContains("/domain/")) continue
        found = true
        val rel = f.relTo(root)
        val content = f.readText()
        when {
            FORBIDDEN_ANNOTATIONS.containsMatchIn(content) ->
                result.add(failFinding(rel, "domain/ 클래스에 Spring 어노테이션 사용 금지"))
            FORBIDDEN_PERSISTENCE_IMPORT.containsMatchIn(content) ->
                result.add(failFinding(rel, "domain/ 클래스에 JPA(jakarta.persistence) 의존 금지 — infrastructure/persistence의 JpaEntity+Mapper로 분리"))
            else ->
                result.add(passFinding("$rel (domain 순수성)"))
        }
    }
    if (!found) result.add(skipFinding("domain/ Kotlin 파일 없음"))
    return result
}

package harness.rules

import harness.*
import java.io.File

private val FORBIDDEN_ANNOTATIONS = Regex("@Service|@Component|@Repository|@Controller|@RestController")

// domain/ must not depend on any framework/ORM. JPA annotations(@Entity/@Embeddable, etc.) can't be
// used without importing jakarta.persistence, so this import is used as the detection signal.
// (Matching just the annotation string could falsely flag a mention inside a doc comment, so the
// check is based on the import instead.)
private val FORBIDDEN_PERSISTENCE_IMPORT = Regex("""import\s+jakarta\.persistence""")

/** [4] domain/ purity — no dependency on frameworks like Spring/JPA */
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
                result.add(failFinding(rel, "Spring annotations may not be used on a domain/ class"))
            FORBIDDEN_PERSISTENCE_IMPORT.containsMatchIn(content) ->
                result.add(failFinding(rel, "a domain/ class may not depend on JPA(jakarta.persistence) — separate it into a JpaEntity+Mapper under infrastructure/persistence"))
            else ->
                result.add(passFinding("$rel (domain purity)"))
        }
    }
    if (!found) result.add(skipFinding("no domain/ Kotlin files"))
    return result
}

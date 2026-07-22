package harness.rules

import harness.*
import java.io.File

private val BLOCK_COMMENT = Regex("""/\*[\s\S]*?\*/""")
private val LINE_COMMENT = Regex("""//[^\n]*""")

private fun stripComments(content: String): String =
    content.replace(BLOCK_COMMENT, "").replace(LINE_COMMENT, "")

private val INFRA_IMPORT =
    Regex("""^import\s+com\.example\.accountservice\.\w+\.infrastructure\b""", RegexOption.MULTILINE)

/**
 * [R2] interface-no-infrastructure — interfaces/(REST controllers, etc.) may not directly import
 * infrastructure/. It must depend only on application/(layer-architecture.md).
 */
fun checkInterfaceNoInfrastructure(rootPath: String): RuleResult {
    val root = File(rootPath)
    val result = RuleResult("interface-no-infrastructure")
    var found = false

    for (f in collectKtFiles(root)) {
        if (!f.pathContains("/interfaces/")) continue
        found = true
        val rel = f.relTo(root)
        val code = stripComments(f.readText())
        if (INFRA_IMPORT.containsMatchIn(code)) {
            result.add(failFinding(rel, "interfaces/ may not directly import infrastructure/ — it must depend only on application/ (layer-architecture.md)"))
        } else {
            result.add(passFinding("$rel (interfaces does not depend on infrastructure)"))
        }
    }

    if (!found) result.add(skipFinding("no interfaces/ Kotlin files"))
    return result
}

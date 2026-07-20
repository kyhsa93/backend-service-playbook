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
 * [R2] interface-no-infrastructure — interfaces/(REST 컨트롤러 등)는 infrastructure/를 직접
 * import할 수 없다. application/ 만 의존해야 한다 (layer-architecture.md).
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
            result.add(failFinding(rel, "interfaces/ 는 infrastructure/를 직접 import할 수 없음 — application/만 의존해야 함 (layer-architecture.md)"))
        } else {
            result.add(passFinding("$rel (interfaces가 infrastructure 미의존)"))
        }
    }

    if (!found) result.add(skipFinding("interfaces/ Kotlin 파일 없음"))
    return result
}

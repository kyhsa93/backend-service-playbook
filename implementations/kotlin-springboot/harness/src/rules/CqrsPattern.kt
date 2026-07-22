package harness.rules

import harness.*
import java.io.File

// FAILs if a file under application/query/ depends on a write-model Repository(AccountRepository etc.,
// a *Repository type). A Query Service must depend only on a read-only interface(AccountQuery etc.)
// (cqrs-pattern.md). Ported from the nestjs harness's cqrs-pattern evaluator into a Kotlin idiom.
private val WRITE_REPOSITORY_REF = Regex("""\b\w*Repository\b""")

// Catching a mention inside a comment(e.g. "separated from the write-model AccountRepository") would
// be a false positive, so line/block comments are stripped first to look only at real code
// dependencies.
private val BLOCK_COMMENT = Regex("""/\*[\s\S]*?\*/""")
private val LINE_COMMENT = Regex("""//[^\n]*""")

private fun stripComments(content: String): String =
    content.replace(BLOCK_COMMENT, "").replace(LINE_COMMENT, "")

/** [14] CQRS — application/query/ must not depend on the write-model Repository (only a read-only Query interface) */
fun checkCqrsPattern(rootPath: String): RuleResult {
    val root = File(rootPath)
    val result = RuleResult("cqrs-pattern")
    var found = false
    for (f in collectKtFiles(root)) {
        if (!f.pathContains("/application/query/")) continue
        found = true
        val rel = f.relTo(root)
        val code = stripComments(f.readText())
        if (WRITE_REPOSITORY_REF.containsMatchIn(code)) {
            result.add(failFinding(rel, "application/query/ must not depend on the write-model Repository(*Repository) — use only a read-only Query interface(AccountQuery etc.) (cqrs-pattern.md)"))
        } else {
            result.add(passFinding("$rel (Query does not depend on a Repository)"))
        }
    }
    if (!found) result.add(skipFinding("no application/query/ Kotlin files"))
    return result
}

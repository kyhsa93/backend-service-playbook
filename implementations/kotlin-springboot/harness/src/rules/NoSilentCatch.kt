package harness.rules

import harness.*
import java.io.File

private val BLOCK_COMMENT = Regex("""/\*[\s\S]*?\*/""")
private val LINE_COMMENT = Regex("""//[^\n]*""")

private fun stripComments(content: String): String =
    content.replace(BLOCK_COMMENT, "").replace(LINE_COMMENT, "")

// Narrowly catches only completely empty catch blocks(nothing but whitespace/newlines between the
// braces). If there's so much as a single character — logging, a rethrow, whatever — it won't match,
// so there's no risk of falsely flagging normal patterns this repository actually uses, like
// `catch (e: Exception) { logger.atError()... }` or `runCatching { }.onFailure { logger.error(...) }`.
private val EMPTY_CATCH = Regex("""catch\s*\([^)]*\)\s*\{\s*}""")

/**
 * [R10] no-silent-catch — forbids a completely empty catch block in application/, infrastructure/
 * that catches an exception without either logging or rethrowing it(observability.md). Scoped to a
 * narrow blocklist(empty blocks only) to minimize false-positive risk — a broader rule like "fail if
 * there's no logger call inside the catch" was not adopted, since it risks flagging normal patterns
 * this repository actually uses, such as runCatching/custom helpers.
 */
fun checkNoSilentCatch(rootPath: String): RuleResult {
    val root = File(rootPath)
    val result = RuleResult("no-silent-catch")
    var found = false

    for (f in collectKtFiles(root)) {
        if (!(f.pathContains("/application/") || f.pathContains("/infrastructure/"))) continue
        found = true
        val rel = f.relTo(root)
        val code = stripComments(f.readText())
        if (EMPTY_CATCH.containsMatchIn(code)) {
            result.add(failFinding(rel, "empty catch blocks are forbidden — must log or rethrow (observability.md)"))
        } else {
            result.add(passFinding("$rel (no empty catch blocks)"))
        }
    }

    if (!found) result.add(skipFinding("no application/, infrastructure/ Kotlin files"))
    return result
}

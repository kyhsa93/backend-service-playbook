package harness.rules

import harness.*
import java.io.File

// Catching a mention inside a comment would be a false positive, so line/block comments are stripped
// first to look only at real interface declarations(the same rationale as the cqrs-pattern.md rule).
private val BLOCK_COMMENT = Regex("""/\*[\s\S]*?\*/""")
private val LINE_COMMENT = Regex("""//[^\n]*""")

private fun stripComments(content: String): String =
    content.replace(BLOCK_COMMENT, "").replace(LINE_COMMENT, "")

// Only catches *Repository / *Query interface declarations inside domain/ or application/query/.
// Implementation classes(RepositoryImpl) or JPA interfaces live in infrastructure/, so they're already
// excluded by the path filter.
private val INTERFACE_DECL = Regex("""\binterface\s+(\w*(?:Repository|Query))\b[^{]*\{""")

private val METHOD_DECL = Regex("""\bfun\s+(\w+)\s*\(""")

// Narrowly catches violations of the find<Noun>s/save<Noun>/delete<Noun> rule defined by the root's
// repository-pattern.md(only as a blocklist, to avoid false positives) — a broader positive-match
// grammar could falsely flag a legitimate method like hasTransactionWithReference.
private data class AntiPattern(val matches: (String) -> Boolean, val reason: String)

private const val DOC_REF = "root repository-pattern.md — reads must be unified as find<Noun>s, writes as save<Noun>, deletes as delete<Noun>"

private val ANTI_PATTERNS =
    listOf(
        AntiPattern({ Regex("^findBy[A-Z]").containsMatchIn(it) }, "find...By... form is forbidden, both list and single-record reads must be unified as find<Noun>s ($DOC_REF)"),
        AntiPattern({ it == "findAll" }, "findAll(a bare read with no noun) is forbidden, name the target noun via find<Noun>s ($DOC_REF)"),
        AntiPattern({ it.startsWith("count") }, "a separate count* method is forbidden, find<Noun>s must return the count together as Pair<List<T>, Long> ($DOC_REF)"),
        AntiPattern({ it == "save" }, "save(a bare write with no noun) is forbidden, name the target noun via save<Noun> ($DOC_REF)"),
        AntiPattern({ it == "delete" }, "delete(a bare delete with no noun) is forbidden, name the target noun via delete<Noun> ($DOC_REF)"),
        AntiPattern(
            { it.startsWith("update") },
            "a separate update method is forbidden — state changes must only happen through an Aggregate domain method(deposit()/suspend(), etc.) and be persisted via save<Noun> ($DOC_REF)",
        ),
    )

/**
 * Accounting for nested braces, returns the inner text up to the '}' matching the '{' at
 * openBraceIndex. Returns null if unbalanced(parse failure).
 */
private fun extractBalancedBody(code: String, openBraceIndex: Int): String? {
    if (code.getOrNull(openBraceIndex) != '{') return null
    var depth = 0
    for (i in openBraceIndex until code.length) {
        when (code[i]) {
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return code.substring(openBraceIndex + 1, i)
            }
        }
    }
    return null
}

/**
 * [15] Repository/Query method naming — a *Repository, *Query interface inside domain/,
 * application/query/ must follow find<Noun>s naming for reads / save<Noun> for writes / delete<Noun>
 * for deletes(repository-pattern.md). Implementations under infrastructure/ and internal Spring Data
 * JPA interfaces are not targeted(derived query methods are allowed as an implementation detail).
 */
fun checkRepositoryNaming(rootPath: String): RuleResult {
    val root = File(rootPath)
    val result = RuleResult("repository-naming")
    var found = false

    for (f in collectKtFiles(root)) {
        if (f.pathContains("/infrastructure/")) continue
        if (!(f.pathContains("/domain/") || f.pathContains("/application/query/"))) continue

        val code = stripComments(f.readText())
        val rel = f.relTo(root)

        for (m in INTERFACE_DECL.findAll(code)) {
            val interfaceName = m.groupValues[1]
            val body = extractBalancedBody(code, m.range.last) ?: continue
            found = true

            var interfaceHasFailure = false
            for (methodMatch in METHOD_DECL.findAll(body)) {
                val methodName = methodMatch.groupValues[1]
                val violation = ANTI_PATTERNS.firstOrNull { it.matches(methodName) }
                if (violation != null) {
                    interfaceHasFailure = true
                    result.add(failFinding("$rel ($interfaceName.$methodName)", violation.reason))
                }
            }
            if (!interfaceHasFailure) {
                result.add(passFinding("$rel ($interfaceName)"))
            }
        }
    }

    if (!found) result.add(skipFinding("no Repository/Query interface inside domain/, application/query/"))
    return result
}

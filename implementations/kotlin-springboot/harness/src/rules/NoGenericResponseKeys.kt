package harness.rules

import harness.*
import java.io.File

private val BLOCK_COMMENT = Regex("""/\*[\s\S]*?\*/""")
private val LINE_COMMENT = Regex("""//[^\n]*""")

private fun stripComments(content: String): String =
    content.replace(BLOCK_COMMENT, "").replace(LINE_COMMENT, "")

// interfaces/rest/, application/query/, application/command/ are where DTOs actually serialized as
// HTTP responses live(excluding Requests — the Result/Response kind)(api-response.md,
// layer-architecture.md). domain/, infrastructure/ aren't response schemas exposed directly to
// clients, so they're excluded from scope.
private fun inResponseScope(f: File): Boolean =
    f.pathContains("/interfaces/") || f.pathContains("/application/query/") || f.pathContains("/application/command/")

private val DATA_CLASS_DECL = Regex("""\bdata class\s+(\w+)\s*\(""")

private fun extractBalancedParens(code: String, openParenIndex: Int): String? {
    if (code.getOrNull(openParenIndex) != '(') return null
    var depth = 0
    for (i in openParenIndex until code.length) {
        when (code[i]) {
            '(' -> depth++
            ')' -> {
                depth--
                if (depth == 0) return code.substring(openParenIndex + 1, i)
            }
        }
    }
    return null
}

// Only targets a property holding a list-response array(a List<...> type) — broadly catching a
// coincidentally same-named field inside a single-record response(e.g. an unrelated simple value
// field) would be a false positive, so this is narrowed to the "generic key in a list response"
// meaning that the root actually forbids.
private val FORBIDDEN_LIST_PROPERTY = Regex("""\bval\s+(result|data|items)\s*:\s*List<""")

private const val DOC_REF = "root api-response.md — a list-response key name must be the domain object's plural form; generic keys like result/data/items are forbidden"

/**
 * no-generic-response-keys — fails if a list-response data class(a Result/Response DTO in
 * interfaces/, application/query/, application/command/) exposes a List<...> property under a generic
 * key like result/data/items — it must use the domain object's plural form(transactions, payments,
 * etc.)(api-response.md).
 */
fun checkNoGenericResponseKeys(rootPath: String): RuleResult {
    val root = File(rootPath)
    val result = RuleResult("no-generic-response-keys")
    var found = false

    for (f in collectKtFiles(root)) {
        if (!inResponseScope(f)) continue
        val code = stripComments(f.readText())
        val rel = f.relTo(root)

        for (m in DATA_CLASS_DECL.findAll(code)) {
            val className = m.groupValues[1]
            val body = extractBalancedParens(code, m.range.last) ?: continue
            found = true

            val violations = FORBIDDEN_LIST_PROPERTY.findAll(body).map { it.groupValues[1] }.toList()
            if (violations.isNotEmpty()) {
                result.add(
                    failFinding(
                        "$rel ($className)",
                        "using a generic key(${violations.joinToString()}) for a List<...> property is forbidden — it must be named after the domain object's plural form ($DOC_REF)",
                    ),
                )
            } else {
                result.add(passFinding("$rel ($className)"))
            }
        }
    }

    if (!found) result.add(skipFinding("no data class inside interfaces/, application/query/, application/command/"))
    return result
}

package harness.rules

import harness.*
import java.io.File

private val BLOCK_COMMENT = Regex("""/\*[\s\S]*?\*/""")
private val LINE_COMMENT = Regex("""//[^\n]*""")

private fun stripComments(content: String): String =
    content.replace(BLOCK_COMMENT, "").replace(LINE_COMMENT, "")

private val ERROR_RESPONSE_DECL = Regex("""\bdata class\s+(\w*ErrorResponse\w*)\s*\(""")
private val PROPERTY_DECL = Regex("""\bval\s+(\w+)\s*:\s*([\w<>?.]+)""")

private val REQUIRED_FIELDS =
    mapOf(
        "statusCode" to Regex("""^(Int|Long|Number)\??$"""),
        "code" to Regex("""^String\??$"""),
        "message" to Regex("""^(String|List<String>|Any)\??$"""),
        "error" to Regex("""^String\??$"""),
    )

/**
 * Accounting for nested parentheses, returns the inner text up to the ')' matching the '(' at
 * openParenIndex. Same purpose as extractBalancedBody in RepositoryNaming.kt, but targets a paren pair.
 */
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

/**
 * [S2] error-response-schema — checks that the error-response data class constructed by the global
 * exception handler(GlobalExceptionHandler, etc.) has exactly the 4 fields the root requires
 * (statusCode: number, code: string, message: string|array, error: string)(error-handling.md). Field
 * names map directly to JSON serialization names, so even the casing must match exactly. Checks every
 * data class ending in `*ErrorResponse` inside `interfaces/`, regardless of domain name — not
 * hardcoded to the single Account domain.
 */
fun checkErrorResponseSchema(rootPath: String): RuleResult {
    val root = File(rootPath)
    val result = RuleResult("error-response-schema")
    var found = false

    for (f in collectKtFiles(root)) {
        val code = stripComments(f.readText())
        val rel = f.relTo(root)

        for (m in ERROR_RESPONSE_DECL.findAll(code)) {
            val className = m.groupValues[1]
            val body = extractBalancedParens(code, m.range.last) ?: continue
            found = true

            val properties = PROPERTY_DECL.findAll(body).map { it.groupValues[1] to it.groupValues[2] }.toList()
            val propertyNames = properties.map { it.first }

            val missing = REQUIRED_FIELDS.keys.filter { it !in propertyNames }
            val extra = propertyNames.filter { it !in REQUIRED_FIELDS.keys }

            if (missing.isNotEmpty() || extra.isNotEmpty()) {
                val reason =
                    buildString {
                        if (missing.isNotEmpty()) append("missing fields: ${missing.joinToString()}. ")
                        if (extra.isNotEmpty()) append("disallowed fields: ${extra.joinToString()}. ")
                        append("must have exactly the 4 fields statusCode/code/message/error (error-handling.md)")
                    }
                result.add(failFinding("$rel ($className)", reason))
                continue
            }

            val typeMismatches =
                properties.mapNotNull { (name, type) ->
                    val expected = REQUIRED_FIELDS[name] ?: return@mapNotNull null
                    if (expected.matches(type)) null else "$name: $type"
                }

            if (typeMismatches.isNotEmpty()) {
                result.add(
                    failFinding(
                        "$rel ($className)",
                        "field types don't match expectations(${typeMismatches.joinToString()}) — statusCode must be numeric, code/error must be String, message must be String(or an array) (error-handling.md)",
                    ),
                )
            } else {
                result.add(passFinding("$rel ($className)"))
            }
        }
    }

    if (!found) result.add(skipFinding("no *ErrorResponse data class"))
    return result
}

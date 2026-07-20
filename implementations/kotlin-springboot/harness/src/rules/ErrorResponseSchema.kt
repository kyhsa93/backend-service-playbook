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
 * 중첩 괄호를 고려해 openParenIndex 위치의 '('에 대응하는 ')'까지의 내부 텍스트를 반환한다.
 * RepositoryNaming.kt의 extractBalancedBody와 동일한 취지이나 괄호 쌍을 대상으로 한다.
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
 * [S2] error-response-schema — 전역 예외 처리기(GlobalExceptionHandler 등)가 구성하는 에러 응답
 * data class가 root가 요구하는 정확히 4필드(statusCode: number, code: string, message: string|array,
 * error: string)를 갖는지 확인한다(error-handling.md). 필드명은 JSON 직렬화 이름과 그대로 매핑되므로
 * 대소문자까지 정확히 일치해야 한다. `interfaces/` 안에서 도메인 이름과 무관하게 `*ErrorResponse`로
 * 끝나는 data class를 전부 검사한다 — Account 도메인 하나에 하드코딩하지 않는다.
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
                        if (missing.isNotEmpty()) append("누락된 필드: ${missing.joinToString()}. ")
                        if (extra.isNotEmpty()) append("허용되지 않는 필드: ${extra.joinToString()}. ")
                        append("정확히 statusCode/code/message/error 4필드여야 함 (error-handling.md)")
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
                        "필드 타입이 예상과 다름(${typeMismatches.joinToString()}) — statusCode는 숫자, code/error는 String, message는 String(또는 배열) (error-handling.md)",
                    ),
                )
            } else {
                result.add(passFinding("$rel ($className)"))
            }
        }
    }

    if (!found) result.add(skipFinding("*ErrorResponse data class 없음"))
    return result
}

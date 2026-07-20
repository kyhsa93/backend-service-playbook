package harness.rules

import harness.*
import java.io.File

private val BLOCK_COMMENT = Regex("""/\*[\s\S]*?\*/""")
private val LINE_COMMENT = Regex("""//[^\n]*""")

private fun stripComments(content: String): String =
    content.replace(BLOCK_COMMENT, "").replace(LINE_COMMENT, "")

// interfaces/rest/, application/query/, application/command/ 가 실제로 HTTP 응답으로 직렬화되는
// DTO(Request 제외 — Result/Response류)가 위치하는 곳이다(api-response.md, layer-architecture.md).
// domain/, infrastructure/는 클라이언트에 직접 노출되는 응답 스키마가 아니므로 대상에서 뺀다.
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

// 목록 응답의 배열을 담는 프로퍼티(List<...> 타입)만 대상 — 단건 응답 안의 우연한 동명 필드(예:
// 별도 의미의 단순 값 필드)까지 넓게 잡으면 오탐이 되므로, root가 실제로 금지하는 "목록 응답의
// 범용 키" 의미로 좁힌다.
private val FORBIDDEN_LIST_PROPERTY = Regex("""\bval\s+(result|data|items)\s*:\s*List<""")

private const val DOC_REF = "루트 api-response.md — 목록 조회 응답의 키 이름은 도메인 객체 복수형이어야 하고 result/data/items 같은 범용 키는 금지"

/**
 * no-generic-response-keys — 목록 조회 응답 data class(Result/Response DTO, interfaces/,
 * application/query/, application/command/)가 List<...> 프로퍼티를 result/data/items 같은
 * 범용 키로 노출하면 실패한다 — 도메인 객체 복수형(transactions, payments 등)을 써야 한다
 * (api-response.md).
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
                        "List<...> 프로퍼티에 범용 키(${violations.joinToString()}) 사용 금지 — 도메인 객체 복수형으로 이름을 지어야 함 ($DOC_REF)",
                    ),
                )
            } else {
                result.add(passFinding("$rel ($className)"))
            }
        }
    }

    if (!found) result.add(skipFinding("interfaces/, application/query/, application/command/ 안의 data class 없음"))
    return result
}

package harness.rules

import harness.*
import java.io.File

// 주석 안의 언급까지 잡으면 오탐이 되므로, 실제 인터페이스 선언만 보도록 라인/블록 주석을 먼저 제거한다
// (cqrs-pattern.md 규칙과 동일한 취지).
private val BLOCK_COMMENT = Regex("""/\*[\s\S]*?\*/""")
private val LINE_COMMENT = Regex("""//[^\n]*""")

private fun stripComments(content: String): String =
    content.replace(BLOCK_COMMENT, "").replace(LINE_COMMENT, "")

// domain/ 또는 application/query/ 안의 *Repository / *Query 인터페이스 선언부만 잡는다.
// 구현 클래스(RepositoryImpl)나 JPA 인터페이스는 infrastructure/에 있으므로 경로 필터로 이미 제외된다.
private val INTERFACE_DECL = Regex("""\binterface\s+(\w*(?:Repository|Query))\b[^{]*\{""")

private val METHOD_DECL = Regex("""\bfun\s+(\w+)\s*\(""")

// root repository-pattern.md가 정의하는 find<Noun>s/save<Noun>/delete<Noun> 규칙 위반을
// 좁게(false positive를 피하기 위해 blocklist로만) 잡는다 — 넓은 positive-match 문법을 쓰면
// hasTransactionWithReference 같은 정상 메서드까지 오탐할 수 있다.
private data class AntiPattern(val matches: (String) -> Boolean, val reason: String)

private const val DOC_REF = "루트 repository-pattern.md — 조회는 find<Noun>s, 저장은 save<Noun>, 삭제는 delete<Noun> 하나로 통일해야 함"

private val ANTI_PATTERNS =
    listOf(
        AntiPattern({ Regex("^findBy[A-Z]").containsMatchIn(it) }, "find...By... 형태 금지, 목록/단건 조회는 find<Noun>s 하나로 통일 ($DOC_REF)"),
        AntiPattern({ it == "findAll" }, "findAll(명사 없는 bare 조회) 금지, find<Noun>s로 대상 명사를 명시 ($DOC_REF)"),
        AntiPattern({ it.startsWith("count") }, "count* 별도 메서드 금지, find<Noun>s가 Pair<List<T>, Long>로 개수를 함께 반환해야 함 ($DOC_REF)"),
        AntiPattern({ it == "save" }, "save(명사 없는 bare 저장) 금지, save<Noun>로 대상 명사를 명시 ($DOC_REF)"),
        AntiPattern({ it == "delete" }, "delete(명사 없는 bare 삭제) 금지, delete<Noun>로 대상 명사를 명시 ($DOC_REF)"),
    )

/**
 * 중첩 괄호를 고려해 openBraceIndex 위치의 '{'에 대응하는 '}'까지의 내부 텍스트를 반환한다.
 * 짝이 맞지 않으면(파싱 실패) null.
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
 * [15] Repository/Query 메서드 네이밍 — domain/, application/query/ 안의 *Repository, *Query
 * 인터페이스는 조회 find<Noun>s / 저장 save<Noun> / 삭제 delete<Noun> 네이밍을 따라야 한다
 * (repository-pattern.md). infrastructure/의 구현체·내부 Spring Data JPA 인터페이스는 대상이 아니다
 * (derived query 메서드는 구현 세부사항으로 허용).
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

    if (!found) result.add(skipFinding("domain/, application/query/ 안의 Repository/Query 인터페이스 없음"))
    return result
}

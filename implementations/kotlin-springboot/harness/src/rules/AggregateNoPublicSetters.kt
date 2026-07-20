package harness.rules

import harness.*
import java.io.File

private val BLOCK_COMMENT = Regex("""/\*[\s\S]*?\*/""")
private val LINE_COMMENT = Regex("""//[^\n]*""")

private fun stripComments(content: String): String =
    content.replace(BLOCK_COMMENT, "").replace(LINE_COMMENT, "")

// 이 저장소의 Aggregate/Entity 관용구: `class X private constructor()` + companion object 팩토리
// (create/reconstitute) + 모든 var 프로퍼티는 private set (tactical-ddd.md). Value Object는
// `data class` + `val`(불변)이라 애초에 var가 없어 대상이 아니다 — 이 클래스 선언 패턴에 매치되는
// 것만 검사 대상으로 좁혀서 data class/enum/sealed class 등을 오탐하지 않는다.
private val AGGREGATE_CLASS_DECL = Regex("""\bclass\s+(\w+)\s+private\s+constructor\s*\([^)]*\)\s*\{""")

// 클래스 바디 최상단에 나열된 프로퍼티 선언(`var NAME: Type`)만 잡는다 — 줄 맨 앞(공백 제외)에
// var가 와야 하므로, `private val ...` 처럼 다른 접근제어자가 이미 붙은 줄이나 함수 안의 로컬 코드는
// 매치되지 않는다.
private val VAR_PROPERTY_LINE = Regex("""^\s*var\s+(\w+)\s*:""")

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
 * 프로퍼티 선언부만 스캔 대상으로 좁히기 위해 `companion object` 또는 첫 `fun` 선언 이전까지만
 * 잘라낸다 — 이 저장소의 모든 Aggregate/Entity(Account/Payment/Refund/Card/Credential/Transaction)가
 * "프로퍼티 나열 → companion object → 도메인 메서드" 순서를 동일하게 따르므로, 도메인 메서드 내부의
 * 지역 변수를 프로퍼티로 오탐하지 않는다.
 */
private fun propertyZone(body: String): String {
    val companionIdx = body.indexOf("companion object").let { if (it == -1) body.length else it }
    val funIdx = Regex("""\bfun\s+\w+\s*\(""").find(body)?.range?.first ?: body.length
    val end = minOf(companionIdx, funIdx)
    return body.substring(0, end)
}

/**
 * [R4] aggregate-no-public-setters — domain/의 Aggregate/Entity(`class X private constructor()`
 * 관용구)는 모든 `var` 프로퍼티가 `private set`이어야 한다. 공개 setter가 있으면 외부에서
 * `account.status = ...`처럼 도메인 메서드를 우회해 직접 대입할 수 있게 되어, "상태 변경은 반드시
 * 도메인 메서드를 통해서만"이라는 원칙(tactical-ddd.md)이 깨진다.
 */
fun checkAggregateNoPublicSetters(rootPath: String): RuleResult {
    val root = File(rootPath)
    val result = RuleResult("aggregate-no-public-setters")
    var found = false

    for (f in collectKtFiles(root)) {
        if (!f.pathContains("/domain/")) continue
        val code = stripComments(f.readText())
        val rel = f.relTo(root)

        for (m in AGGREGATE_CLASS_DECL.findAll(code)) {
            val className = m.groupValues[1]
            val body = extractBalancedBody(code, m.range.last) ?: continue
            found = true
            val zoneLines = propertyZone(body).lines()

            var classHasFailure = false
            for (i in zoneLines.indices) {
                val varMatch = VAR_PROPERTY_LINE.find(zoneLines[i]) ?: continue
                val propName = varMatch.groupValues[1]
                val nextNonBlank = zoneLines.drop(i + 1).map { it.trim() }.firstOrNull { it.isNotEmpty() }
                if (nextNonBlank != "private set") {
                    classHasFailure = true
                    result.add(
                        failFinding(
                            "$rel ($className.$propName)",
                            "Aggregate/Entity의 var 프로퍼티는 private set이어야 함 — 공개 setter로 도메인 메서드를 우회할 수 없어야 함 (tactical-ddd.md)",
                        ),
                    )
                }
            }
            if (!classHasFailure) result.add(passFinding("$rel ($className)"))
        }
    }

    if (!found) result.add(skipFinding("domain/ 안의 'class X private constructor()' Aggregate/Entity 없음"))
    return result
}

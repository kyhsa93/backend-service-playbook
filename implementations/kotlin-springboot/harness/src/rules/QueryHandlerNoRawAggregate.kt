package harness.rules

import harness.*
import java.io.File

private val BLOCK_COMMENT = Regex("""/\*[\s\S]*?\*/""")
private val LINE_COMMENT = Regex("""//[^\n]*""")

private fun stripComments(content: String): String =
    content.replace(BLOCK_COMMENT, "").replace(LINE_COMMENT, "")

// domain/의 Aggregate/Entity 관용구(`class X private constructor()`, tactical-ddd.md,
// aggregate-no-public-setters와 동일한 패턴) — 도메인 이름을 하드코딩하지 않고 각 BC(경로의
// domain/ 바로 앞 세그먼트)가 실제로 갖는 Aggregate/Entity 클래스명을 구조적으로 추출한다.
private val AGGREGATE_CLASS_DECL = Regex("""\bclass\s+(\w+)\s+private\s+constructor\s*\(""")

private val SERVICE_ANNOTATION = Regex("""@Service\b""")
private val REST_CONTROLLER_ANNOTATION = Regex("""@RestController\b""")

private val FUN_DECL = Regex("""\bfun\s+(\w+)\s*\(""")
private val RETURN_TYPE_AFTER_PARENS = Regex("""^\s*:\s*([^{=]+?)\s*[{=]""")

private fun findMatchingParenClose(code: String, openParenIndex: Int): Int? {
    if (code.getOrNull(openParenIndex) != '(') return null
    var depth = 0
    for (i in openParenIndex until code.length) {
        when (code[i]) {
            '(' -> depth++
            ')' -> {
                depth--
                if (depth == 0) return i
            }
        }
    }
    return null
}

private fun collectAggregateNamesByBc(root: File): Map<String, Set<String>> {
    val map = mutableMapOf<String, MutableSet<String>>()
    for (f in collectKtFiles(root)) {
        if (!f.pathContains("/domain/")) continue
        val bc = f.segmentBefore("domain") ?: continue
        val code = stripComments(f.readText())
        for (m in AGGREGATE_CLASS_DECL.findAll(code)) {
            map.getOrPut(bc) { mutableSetOf() }.add(m.groupValues[1])
        }
    }
    return map
}

private fun checkFile(file: File, root: File, forbiddenNames: Set<String>, result: RuleResult) {
    val code = stripComments(file.readText())
    val rel = file.relTo(root)

    var fileHasFailure = false
    for (m in FUN_DECL.findAll(code)) {
        val funcName = m.groupValues[1]
        val closeParenIdx = findMatchingParenClose(code, m.range.last) ?: continue
        val rest = code.substring(closeParenIdx + 1)
        val retMatch = RETURN_TYPE_AFTER_PARENS.find(rest) ?: continue
        val returnType = retMatch.groupValues[1].trim()

        val leaked = forbiddenNames.filter { Regex("""\b${Regex.escape(it)}\b""").containsMatchIn(returnType) }
        if (leaked.isNotEmpty()) {
            fileHasFailure = true
            result.add(
                failFinding(
                    "$rel ($funcName)",
                    "반환 타입($returnType)이 raw Domain Aggregate(${leaked.joinToString()})를 그대로 노출함 — 전용 Result/DTO 타입을 반환해야 함 (api-response.md)",
                ),
            )
        }
    }
    if (!fileHasFailure) result.add(passFinding("$rel (raw Aggregate 미반환)"))
}

/**
 * query-handler-no-raw-aggregate — Query Service(application/query/의 `@Service` 클래스)와
 * REST Controller(`@RestController`)는 자신이 속한 BC의 raw Domain Aggregate/Entity
 * (`class X private constructor()`)를 함수 반환 타입으로 그대로 노출하면 안 된다 — 전용
 * Result/DTO data class를 반환해야 한다(api-response.md). application/query/ 안이라도 `*Query`
 * 읽기 전용 포트 인터페이스(예: AccountQuery, cqrs-pattern.md)는 Query Service 내부에서만 쓰이는
 * 경계 밖의 것이라 raw Aggregate를 반환해도 정당하므로(`@Service`가 없으므로) 대상이 아니다.
 */
fun checkQueryHandlerNoRawAggregate(rootPath: String): RuleResult {
    val root = File(rootPath)
    val result = RuleResult("query-handler-no-raw-aggregate")
    var found = false

    val aggregatesByBc = collectAggregateNamesByBc(root)

    for (f in collectKtFiles(root)) {
        val content = f.readText()
        val bc =
            when {
                f.pathContains("/application/query/") && SERVICE_ANNOTATION.containsMatchIn(content) -> f.segmentBefore("application")
                f.pathContains("/interfaces/") && REST_CONTROLLER_ANNOTATION.containsMatchIn(content) -> f.segmentBefore("interfaces")
                else -> null
            } ?: continue

        found = true
        checkFile(f, root, aggregatesByBc[bc] ?: emptySet(), result)
    }

    if (!found) result.add(skipFinding("application/query/ 의 @Service Query Service, @RestController 없음"))
    return result
}

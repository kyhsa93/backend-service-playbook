package harness.rules

import harness.*
import java.io.File

private val BLOCK_COMMENT = Regex("""/\*[\s\S]*?\*/""")
private val LINE_COMMENT = Regex("""//[^\n]*""")

private fun stripComments(content: String): String =
    content.replace(BLOCK_COMMENT, "").replace(LINE_COMMENT, "")

// The Aggregate/Entity idiom in domain/(`class X private constructor()`, tactical-ddd.md, the same
// pattern as aggregate-no-public-setters) — structurally extracts the Aggregate/Entity class names
// each BC(the segment right before domain/ in the path) actually has, without hardcoding domain names.
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
                    "the return type($returnType) exposes a raw Domain Aggregate(${leaked.joinToString()}) as-is — a dedicated Result/DTO type must be returned (api-response.md)",
                ),
            )
        }
    }
    if (!fileHasFailure) result.add(passFinding("$rel (does not return a raw Aggregate)"))
}

/**
 * query-handler-no-raw-aggregate — a Query Service(a `@Service` class under application/query/) or a
 * REST Controller(`@RestController`) must not expose the raw Domain Aggregate/Entity of its own BC
 * (`class X private constructor()`) as a function return type — a dedicated Result/DTO data class
 * must be returned(api-response.md). Even inside application/query/, a `*Query` read-only port
 * interface(e.g. AccountQuery, cqrs-pattern.md) is used only inside the Query Service and lies outside
 * this boundary, so it may legitimately return a raw Aggregate(since it has no `@Service`) and is not
 * targeted.
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

    if (!found) result.add(skipFinding("no @Service Query Service under application/query/, no @RestController"))
    return result
}

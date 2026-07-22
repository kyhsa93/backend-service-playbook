package harness.rules

import harness.*
import java.io.File

private val BLOCK_COMMENT = Regex("""/\*[\s\S]*?\*/""")
private val LINE_COMMENT = Regex("""//[^\n]*""")

private fun stripComments(content: String): String =
    content.replace(BLOCK_COMMENT, "").replace(LINE_COMMENT, "")

// This repository's Aggregate/Entity idiom: `class X private constructor()` + a companion object
// factory (create/reconstitute) + every var property is private set (tactical-ddd.md). A Value Object
// is a `data class` + `val`(immutable), so it has no var to begin with and is not targeted — scoping
// the check to only this class-declaration pattern avoids false positives on data class/enum/sealed
// class etc.
private val AGGREGATE_CLASS_DECL = Regex("""\bclass\s+(\w+)\s+private\s+constructor\s*\([^)]*\)\s*\{""")

// Only catches property declarations(`var NAME: Type`) listed at the top level of the class body — var
// must come at the very start of the line(ignoring whitespace), so a line already carrying a different
// access modifier like `private val ...`, or local code inside a function, won't match.
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
 * Truncates the body to just before `companion object` or the first `fun` declaration, to narrow
 * scanning to only the property-declaration section — since every Aggregate/Entity in this repository
 * (Account/Payment/Refund/Card/Credential/Transaction) follows the same
 * "properties listed → companion object → domain methods" order, this avoids falsely treating a local
 * variable inside a domain method as a property.
 */
private fun propertyZone(body: String): String {
    val companionIdx = body.indexOf("companion object").let { if (it == -1) body.length else it }
    val funIdx = Regex("""\bfun\s+\w+\s*\(""").find(body)?.range?.first ?: body.length
    val end = minOf(companionIdx, funIdx)
    return body.substring(0, end)
}

/**
 * [R4] aggregate-no-public-setters — an Aggregate/Entity in domain/(the `class X private
 * constructor()` idiom) must have every `var` property as `private set`. A public setter would allow
 * external code to assign directly, bypassing domain methods, like `account.status = ...` — breaking
 * the principle that "state changes must only ever go through a domain method"(tactical-ddd.md).
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
                            "an Aggregate/Entity's var property must be private set — a public setter must not be able to bypass domain methods (tactical-ddd.md)",
                        ),
                    )
                }
            }
            if (!classHasFailure) result.add(passFinding("$rel ($className)"))
        }
    }

    if (!found) result.add(skipFinding("no 'class X private constructor()' Aggregate/Entity under domain/"))
    return result
}

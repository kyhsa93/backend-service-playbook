package harness.rules

import harness.*
import java.io.File

private val BLOCK_COMMENT = Regex("""/\*[\s\S]*?\*/""")
private val LINE_COMMENT = Regex("""//[^\n]*""")

private fun stripComments(content: String): String =
    content.replace(BLOCK_COMMENT, "").replace(LINE_COMMENT, "")

// Only catches top-level class property declarations(`var`/`val NAME: Type`) — since ordinary Kotlin
// function parameters don't carry the var/val keyword, a legitimate Domain Service pattern that
// receives multiple Aggregates as function parameters, like
// RefundEligibilityService.evaluate(payment: Payment, refund: Refund)(domain-service.md), doesn't
// trip this line-anchored regex.
private fun propertyLineRegexFor(typeName: String) =
    Regex("""^\s*(var|val)\s+\w+\s*:\s*(List<)?$typeName\??>?\b""", RegexOption.MULTILINE)

// payment/domain/ is currently the only case where two Aggregates, Payment and Refund, genuinely
// coexist(the RefundEligibilityService example in domain-service.md). Attempting to generalize this
// (scanning class declarations across every domain/ directory to auto-infer Aggregate pairs) would
// falsely flag legitimate relationships like account/domain/'s Account holding its own child Entity
// under the same `class X private constructor()` pattern, as if it were a "sibling Aggregate" rather
// than "an Aggregate owning its child Entity" — so this rule is scoped down to payment/domain/, where
// a real violation case exists.
private const val SCOPE_DIR = "/payment/domain/"

/**
 * [R5] no-cross-aggregate-reference — in payment/domain/, Payment may not directly reference Refund
 * as a field, nor may Refund reference Payment. Only ID references like `paymentId: String` are
 * allowed(domain-service.md).
 */
fun checkNoCrossAggregateReference(rootPath: String): RuleResult {
    val root = File(rootPath)
    val result = RuleResult("no-cross-aggregate-reference")

    val paymentFile = collectKtFiles(root).find { it.pathContains(SCOPE_DIR) && it.name == "Payment.kt" }
    val refundFile = collectKtFiles(root).find { it.pathContains(SCOPE_DIR) && it.name == "Refund.kt" }

    if (paymentFile == null || refundFile == null) {
        result.add(skipFinding("no payment/domain/Payment.kt or Refund.kt"))
        return result
    }

    checkNoFieldReference(paymentFile, root, "Refund", result)
    checkNoFieldReference(refundFile, root, "Payment", result)
    return result
}

private fun checkNoFieldReference(file: File, root: File, forbiddenType: String, result: RuleResult) {
    val code = stripComments(file.readText())
    val rel = file.relTo(root)
    if (propertyLineRegexFor(forbiddenType).containsMatchIn(code)) {
        result.add(
            failFinding(
                rel,
                "must not directly reference another Aggregate($forbiddenType) as a field — only ID references like ${forbiddenType.replaceFirstChar { it.lowercase() }}Id: String are allowed (domain-service.md)",
            ),
        )
    } else {
        result.add(passFinding("$rel (does not reference the $forbiddenType field)"))
    }
}

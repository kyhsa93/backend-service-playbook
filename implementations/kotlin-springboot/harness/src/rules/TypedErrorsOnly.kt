package harness.rules

import harness.*
import java.io.File

private val BLOCK_COMMENT = Regex("""/\*[\s\S]*?\*/""")
private val LINE_COMMENT = Regex("""//[^\n]*""")

private fun stripComments(content: String): String =
    content.replace(BLOCK_COMMENT, "").replace(LINE_COMMENT, "")

// Only catches construction of a generic exception that takes a string-literal argument right after
// throw — a rethrow wrapping a cause exception like throw SomeException(cause), or a custom sealed
// subclass(e.g. AccountNotFoundException) whose name happens to end in "Exception", won't match(a \b
// boundary plus listing only the exact class names).
private val GENERIC_EXCEPTION_WITH_LITERAL =
    Regex("""\bthrow\s+(RuntimeException|IllegalStateException|IllegalArgumentException|Exception|Error)\s*\(\s*["']""")

/**
 * [S4] typed-errors-only — the domain/application-scoped check for the root's absolute rule("type
 * errors as enums — no free-form strings allowed", AGENTS.md/error-handling.md). This repository's
 * idiom is a `sealed class *Exception` hierarchy(AccountException, CardException, etc.), so directly
 * throwing a generic exception with a string literal, like `RuntimeException("...")`/
 * `IllegalStateException("...")`, in domain/application fails — a concrete subtype of the sealed
 * hierarchy must be thrown instead.
 *
 * A "technical error that isn't a business-rule violation," such as a message-parsing failure in
 * outbox/(shared infrastructure, infrastructure in nature), is not targeted by this rule, which the
 * root error-handling.md enforces only for domain/application — indeed outbox/OutboxConsumer.kt uses
 * IllegalStateException, but since it's outside domain/, application/ it's excluded from scope(the
 * intended scope, confirmed against the actual code).
 */
fun checkTypedErrorsOnly(rootPath: String): RuleResult {
    val root = File(rootPath)
    val result = RuleResult("typed-errors-only")
    var found = false

    for (f in collectKtFiles(root)) {
        if (!(f.pathContains("/domain/") || f.pathContains("/application/"))) continue
        found = true
        val rel = f.relTo(root)
        val code = stripComments(f.readText())
        val violation = GENERIC_EXCEPTION_WITH_LITERAL.find(code)
        if (violation != null) {
            result.add(
                failFinding(
                    rel,
                    "a free-form string exception like '${violation.groupValues[1]}(\"...\")' is forbidden in domain/, application/ — " +
                        "only enum(code)-mapped subtypes of the sealed class exception hierarchy may be thrown (error-handling.md)",
                ),
            )
        } else {
            result.add(passFinding("$rel (uses only typed exceptions)"))
        }
    }

    if (!found) result.add(skipFinding("no domain/, application/ Kotlin files"))
    return result
}

package harness

/** The kind of a Finding — pass, fail, or a skip meaning "not applicable". */
enum class Kind { PASS, FAIL, SKIP }

/** A single item within one rule's section output. For a Skip, name holds the skip message. */
data class Finding(val kind: Kind, val name: String, val reason: String = "")

/** The result returned by a single rule — a section header + the list of Findings to print under it. */
class RuleResult(val section: String) {
    val findings: MutableList<Finding> = mutableListOf()

    fun add(finding: Finding) {
        findings.add(finding)
    }

    fun count(kind: Kind): Int = findings.count { it.kind == kind }
}

fun passFinding(name: String): Finding = Finding(Kind.PASS, name)
fun failFinding(name: String, reason: String): Finding = Finding(Kind.FAIL, name, reason)
fun skipFinding(message: String): Finding = Finding(Kind.SKIP, message)

/** The rule function signature — takes the projectRoot absolute path and returns a RuleResult. */
typealias Rule = (String) -> RuleResult

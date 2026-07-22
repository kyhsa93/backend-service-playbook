package harness.rules

import harness.*
import java.io.File

// Treats all three logging APIs — direct SLF4J use, mu.KotlinLogging(microutils), and
// io.github.oshai.kotlinlogging — as signals. This repository currently uses SLF4J(observability.md),
// but adopting kotlin-logging is also under consideration, so both library names are kept on the
// blocklist.
private val LOGGING_SIGNAL = Regex(
    """import\s+org\.slf4j|import\s+mu\.KotlinLogging|import\s+io\.github\.oshai\.kotlinlogging|LoggerFactory\.getLogger|KotlinLogging\.logger""",
)

/**
 * [R8] no-logging-in-domain — domain/ does no logging (observability.md "no logging in the Domain
 * layer"). An extension of the principle that Aggregates/Domain Services must have no
 * framework/infrastructure dependency — failures are expressed as exceptions, and logging is the
 * responsibility of the Application/Infrastructure layer that catches that exception.
 */
fun checkNoLoggingInDomain(rootPath: String): RuleResult {
    val root = File(rootPath)
    val result = RuleResult("no-logging-in-domain")
    var found = false

    for (f in collectKtFiles(root)) {
        if (!f.pathContains("/domain/")) continue
        found = true
        val rel = f.relTo(root)
        val content = f.readText()
        if (LOGGING_SIGNAL.containsMatchIn(content)) {
            result.add(failFinding(rel, "logging is forbidden in the domain/ layer — SLF4J/kotlin-logging etc. may not be used (observability.md)"))
        } else {
            result.add(passFinding("$rel (no logging used)"))
        }
    }

    if (!found) result.add(skipFinding("no domain/ Kotlin files"))
    return result
}

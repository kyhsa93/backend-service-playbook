package harness.rules

import harness.*
import java.io.File

private val ENV_ACCESS = Regex("""System\.getenv\(""")

/**
 * [R6] no-direct-env-access-outside-config — domain/, application/ may not directly call
 * System.getenv(...). Environment variables must only be bound through a @ConfigurationProperties
 * class in config/, and infrastructure/ must inject and use that property object (config.md).
 */
fun checkNoDirectEnvAccessOutsideConfig(rootPath: String): RuleResult {
    val root = File(rootPath)
    val result = RuleResult("no-direct-env-access-outside-config")
    var found = false

    for (f in collectKtFiles(root)) {
        if (!(f.pathContains("/domain/") || f.pathContains("/application/"))) continue
        found = true
        val rel = f.relTo(root)
        val content = f.readText()
        if (ENV_ACCESS.containsMatchIn(content)) {
            result.add(
                failFinding(
                    rel,
                    "directly calling System.getenv() in domain/, application/ is forbidden — only config/(@ConfigurationProperties) or infrastructure/ may access environment variables (config.md)",
                ),
            )
        } else {
            result.add(passFinding("$rel (no direct environment-variable access)"))
        }
    }

    if (!found) result.add(skipFinding("no domain/, application/ Kotlin files"))
    return result
}

package harness.rules

import harness.*
import java.io.File

private val SCHEDULER_SIGNAL = Regex("""@Scheduled\b|@EnableScheduling\b""")

/**
 * [R9] scheduler-in-infrastructure-only — @Scheduled/@EnableScheduling may not be used in domain/,
 * application/(scheduling.md "the Scheduler belongs to the Infrastructure layer"). This repository
 * actually uses this annotation in outbox/(shared infrastructure, OutboxPoller.kt) and the top-level
 * bootstrap class(AccountServiceApplication.kt) — neither is under a domain/·application/ path, so
 * this is checked with a blocklist approach(fail if inside domain/application) — a whitelist approach
 * requiring "must be inside infrastructure/" would have falsely flagged both outbox/ and the root
 * bootstrap class.
 */
fun checkSchedulerInInfrastructureOnly(rootPath: String): RuleResult {
    val root = File(rootPath)
    val result = RuleResult("scheduler-in-infrastructure-only")
    var found = false

    for (f in collectKtFiles(root)) {
        val content = f.readText()
        if (!SCHEDULER_SIGNAL.containsMatchIn(content)) continue
        found = true
        val rel = f.relTo(root)
        if (f.pathContains("/domain/") || f.pathContains("/application/")) {
            result.add(failFinding(rel, "@Scheduled/@EnableScheduling may not be used in domain/, application/ — place it only in infrastructure/(or shared infrastructure) (scheduling.md)"))
        } else {
            result.add(passFinding("$rel (Scheduler is located outside domain/application)"))
        }
    }

    if (!found) result.add(skipFinding("no @Scheduled/@EnableScheduling usage"))
    return result
}

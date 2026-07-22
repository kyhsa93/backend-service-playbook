package harness.rules

import harness.*
import java.io.File

private val EVENT_PUBLISHER = Regex("ApplicationEventPublisher|@EventListener|\\.publishEvent\\(")

/**
 * [10] A Command Service must go only through the Outbox — direct use of
 * ApplicationEventPublisher/@EventListener/publishEvent() is forbidden (domain-events.md)
 */
fun checkNoEventPublisherInCommand(rootPath: String): RuleResult {
    val root = File(rootPath)
    val result = RuleResult("no-event-publisher-in-command")
    var found = false
    for (f in collectKtFiles(root)) {
        if (!f.pathContains("/application/command/")) continue
        found = true
        val rel = f.relTo(root)
        val content = f.readText()
        if (EVENT_PUBLISHER.containsMatchIn(content)) {
            result.add(failFinding(rel, "a Command Service must not use ApplicationEventPublisher/@EventListener/publishEvent() — must go through the Outbox(domain-events.md)"))
        } else {
            result.add(passFinding("$rel (confirmed it goes through the Outbox)"))
        }
    }
    if (!found) result.add(skipFinding("no Command Service"))
    return result
}

package harness.rules

import harness.*
import java.io.File

/**
 * [12] No synchronous Outbox drain — a Command Service must not reference OutboxRelay/OutboxPoller/
 * OutboxConsumer or call a drain method (domain-events.md).
 *
 * Outbox → queue publishing(`OutboxPoller`) and queue → handler execution(`OutboxConsumer`) are
 * separate components that run independently on their own periodic schedule — a Command Service must
 * return immediately after persisting and must not directly call a drain method. If a synchronous
 * drain were reintroduced, no other rule besides this one would catch it.
 */
// A very simple comment strip is applied so that merely mentioning words like "OutboxRelay"/
// "processPending" for explanatory purposes inside a comment isn't falsely flagged as a violation —
// this harness's other regex-based rules already use a similar level of approximation(extreme edge
// cases like "//" inside a string literal are accepted as a tradeoff).
private fun stripComments(content: String): String =
    content.replace(Regex("""/\*[\s\S]*?\*/"""), "").replace(Regex("""//.*"""), "")

private val FORBIDDEN_SYMBOL = Regex("""\bOutboxRelay\b|\bOutboxPoller\b|\bOutboxConsumer\b""")
private val FORBIDDEN_CALL = Regex("""\.\s*(?:processPending|poll|drainOnce)\s*\(""")

fun checkOutboxNoSyncDrain(rootPath: String): RuleResult {
    val root = File(rootPath)
    val result = RuleResult("outbox-no-sync-drain")
    var found = false
    for (f in collectKtFiles(root)) {
        if (!f.pathContains("/application/command/")) continue
        found = true
        val rel = f.relTo(root)
        val content = stripComments(f.readText())
        val violated = FORBIDDEN_SYMBOL.containsMatchIn(content) || FORBIDDEN_CALL.containsMatchIn(content)
        if (violated) {
            result.add(
                failFinding(
                    rel,
                    "the Command Service references OutboxRelay/OutboxPoller/OutboxConsumer or calls a drain method" +
                        "(processPending/poll/drainOnce) — publishing/consuming from the Outbox → queue is " +
                        "solely the responsibility of an independently periodic-run Poller/Consumer(no synchronous drain allowed, domain-events.md)",
                ),
            )
        } else {
            result.add(passFinding(rel))
        }
    }
    if (!found) result.add(skipFinding("no files under application/command/"))
    return result
}

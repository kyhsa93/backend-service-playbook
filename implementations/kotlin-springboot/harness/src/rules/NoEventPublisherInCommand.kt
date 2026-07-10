package harness.rules

import harness.*
import java.io.File

private val EVENT_PUBLISHER = Regex("ApplicationEventPublisher|@EventListener|\\.publishEvent\\(")

/**
 * [10] Command Service는 Outbox 경유만 허용 — ApplicationEventPublisher/@EventListener/
 * publishEvent() 직접 사용 금지 (domain-events.md)
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
            result.add(failFinding(rel, "Command Service는 ApplicationEventPublisher/@EventListener/publishEvent()를 쓰지 않아야 함 — Outbox 경유(domain-events.md)"))
        } else {
            result.add(passFinding("$rel (Outbox 경유 확인)"))
        }
    }
    if (!found) result.add(skipFinding("Command Service 없음"))
    return result
}

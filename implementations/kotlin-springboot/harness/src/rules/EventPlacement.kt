package harness.rules

import harness.*
import java.io.File

/** [9] event-placement */
fun checkEventPlacement(rootPath: String): RuleResult {
    val root = File(rootPath)
    val result = RuleResult("event-placement")
    var found = false
    val reported = mutableSetOf<String>()

    for (f in collectKtFiles(root)) {
        val name = f.nameWithoutExtension
        val rel = f.relTo(root)
        when {
            name.endsWith("EventHandler") -> {
                found = true
                reported.add(f.path)
                if (f.pathContains("/application/event/")) {
                    result.add(passFinding("$rel (EventHandler)"))
                } else {
                    result.add(failFinding(rel, "an EventHandler must be inside the application/event/ package"))
                }
            }
            name.endsWith("IntegrationEvent") -> {
                found = true
                reported.add(f.path)
                if (f.pathContains("/application/integration-event/")) {
                    result.add(passFinding("$rel (IntegrationEvent)"))
                } else {
                    result.add(failFinding(rel, "an IntegrationEvent must be inside the application/integration-event/ package"))
                }
            }
        }
    }

    // Regardless of the file-name suffix(EventHandler/IntegrationEvent), an @EventListener annotation
    // — which signals Spring's ApplicationEventPublisher-based synchronous in-process event handling —
    // is treated as event-handling code.
    for (f in collectKtFiles(root)) {
        if (f.path in reported) continue
        val content = f.readText()
        if (!content.contains("@EventListener")) continue
        found = true
        val rel = f.relTo(root)
        if (f.pathContains("/application/event/")) {
            result.add(passFinding("$rel (@EventListener)"))
        } else {
            result.add(failFinding(rel, "a class using @EventListener must be inside the application/event/ package"))
        }
    }

    if (!found) result.add(skipFinding("no event handlers"))
    return result
}

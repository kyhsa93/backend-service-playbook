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
                    result.add(failFinding(rel, "EventHandler는 application/event/ 패키지 안에 있어야 함"))
                }
            }
            name.endsWith("IntegrationEvent") -> {
                found = true
                reported.add(f.path)
                if (f.pathContains("/application/integration-event/")) {
                    result.add(passFinding("$rel (IntegrationEvent)"))
                } else {
                    result.add(failFinding(rel, "IntegrationEvent는 application/integration-event/ 패키지 안에 있어야 함"))
                }
            }
        }
    }

    // 파일명 접미사(EventHandler/IntegrationEvent)와 무관하게, Spring의
    // ApplicationEventPublisher 기반 동기 인프로세스 이벤트 처리를 나타내는 @EventListener
    // 애노테이션이 있으면 이벤트 핸들링 코드로 간주한다.
    for (f in collectKtFiles(root)) {
        if (f.path in reported) continue
        val content = f.readText()
        if (!content.contains("@EventListener")) continue
        found = true
        val rel = f.relTo(root)
        if (f.pathContains("/application/event/")) {
            result.add(passFinding("$rel (@EventListener)"))
        } else {
            result.add(failFinding(rel, "@EventListener 사용 클래스는 application/event/ 패키지 안에 있어야 함"))
        }
    }

    if (!found) result.add(skipFinding("이벤트 핸들러 없음"))
    return result
}

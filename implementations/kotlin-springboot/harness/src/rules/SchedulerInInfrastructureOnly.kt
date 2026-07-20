package harness.rules

import harness.*
import java.io.File

private val SCHEDULER_SIGNAL = Regex("""@Scheduled\b|@EnableScheduling\b""")

/**
 * [R9] scheduler-in-infrastructure-only — @Scheduled/@EnableScheduling은 domain/, application/
 * 에 사용할 수 없다 (scheduling.md "Scheduler는 Infrastructure 레이어"). 이 저장소는 outbox/
 * (공유 인프라, OutboxPoller.kt)와 최상위 부트스트랩 클래스(AccountServiceApplication.kt)에서
 * 실제로 이 애노테이션을 쓰는데, 둘 다 domain/·application/ 경로가 아니므로 블록리스트 방식
 * (도메인/애플리케이션 안에 있으면 실패)으로 검사한다 — "infrastructure/ 안에 있는지"를 요구하는
 * 화이트리스트 방식이었다면 outbox/·루트 부트스트랩 클래스 둘 다 오탐했을 것이다.
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
            result.add(failFinding(rel, "@Scheduled/@EnableScheduling는 domain/, application/ 에 사용 금지 — infrastructure/(또는 공유 인프라)에만 배치 (scheduling.md)"))
        } else {
            result.add(passFinding("$rel (Scheduler가 domain/application 밖에 위치)"))
        }
    }

    if (!found) result.add(skipFinding("@Scheduled/@EnableScheduling 사용 없음"))
    return result
}

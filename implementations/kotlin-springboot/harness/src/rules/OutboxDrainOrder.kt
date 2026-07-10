package harness.rules

import harness.*
import java.io.File

/**
 * [12] Outbox 드레인 순서 — save() 호출 뒤에 processPending() 호출 (domain-events.md)
 *
 * OutboxRelay를 참조하는 Command Service가 저장(save) 커밋 이후 반드시
 * processPending()을 호출해 Outbox를 드레인해야 한다. 이 검사는 파일명·배치가 아니라
 * 실제 텍스트 순서를 본다 — 이게 없으면 dual-write 회귀(processPending 호출 삭제,
 * 또는 알림을 직접 호출하는 것으로 되돌림)를 다른 어떤 규칙도 잡아내지 못한다.
 */
fun checkOutboxDrainOrder(rootPath: String): RuleResult {
    val root = File(rootPath)
    val result = RuleResult("outbox-drain-order")
    var found = false
    for (f in collectKtFiles(root)) {
        if (!f.pathContains("/application/command/")) continue
        val content = f.readText()
        if (!content.contains("OutboxRelay")) continue
        found = true
        val rel = f.relTo(root)
        val saveIdx = content.indexOf(".save(")
        val ppIdx = content.indexOf(".processPending(")
        when {
            saveIdx == -1 ->
                result.add(failFinding(rel, "OutboxRelay를 참조하지만 save(...) 호출을 찾을 수 없음"))
            ppIdx == -1 ->
                result.add(failFinding(rel, "OutboxRelay를 참조하지만 processPending() 호출이 없음 — 저장 직후 Outbox 드레인 누락(domain-events.md)"))
            ppIdx < saveIdx ->
                result.add(failFinding(rel, "processPending() 호출이 save(...) 호출보다 먼저 등장함 — 커밋 이후 드레인 순서 위반"))
            else ->
                result.add(passFinding("$rel (save → processPending 순서 확인)"))
        }
    }
    if (!found) result.add(skipFinding("OutboxRelay를 사용하는 Command Service 없음"))
    return result
}

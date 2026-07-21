package harness.rules

import harness.*
import java.io.File

/**
 * [12] Outbox 동기 드레인 금지 — Command Service는 OutboxRelay/OutboxPoller/OutboxConsumer를
 * 참조하거나 드레인 메서드를 호출하면 안 된다 (domain-events.md).
 *
 * Outbox → 큐 발행(`OutboxPoller`)과 큐 → 핸들러 실행(`OutboxConsumer`)은 독립적으로 주기
 * 실행되는 별도 컴포넌트다 — Command Service는 저장 후 곧바로 반환해야 하며 드레인 메서드를
 * 직접 호출하면 안 된다. 동기 드레인을 다시 추가해도 이 규칙 하나가 없으면 어떤 다른 규칙도
 * 잡아내지 못한다.
 */
// 주석 안에서 "OutboxRelay"/"processPending" 같은 단어를 설명 목적으로 언급하는 것까지 위반으로
// 오탐하지 않기 위해 아주 단순한 주석 제거를 거친다 — 이 harness의 다른 정규식 기반 규칙들도
// 이미 비슷한 수준의 근사치를 쓴다(문자열 리터럴 안의 "//" 같은 극단적 엣지 케이스는 감수한다).
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
                    "Command Service가 OutboxRelay/OutboxPoller/OutboxConsumer를 참조하거나 드레인 메서드" +
                        "(processPending/poll/drainOnce)를 호출함 — Outbox → 큐 발행/수신은 독립적으로 주기 " +
                        "실행되는 Poller/Consumer만의 책임이다(동기 드레인 금지, domain-events.md)",
                ),
            )
        } else {
            result.add(passFinding(rel))
        }
    }
    if (!found) result.add(skipFinding("application/command/ 아래 파일 없음"))
    return result
}

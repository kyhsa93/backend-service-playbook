package harness.rules

import harness.*
import java.io.File

/** [11] 트랜잭션 경계 — Command Service에는 없고 Repository.save()에 있어야 함 */
fun checkTransactionBoundary(rootPath: String): RuleResult {
    val root = File(rootPath)
    val result = RuleResult("transaction-boundary")
    var found = false

    for (f in collectKtFiles(root)) {
        if (!f.pathContains("/application/command/")) continue
        found = true
        val rel = f.relTo(root)
        val content = f.readText()
        if (content.contains("@Transactional")) {
            result.add(failFinding(rel, "Command Service에 @Transactional이 있으면 안 됨 — 트랜잭션 경계는 Repository.save()로 이관됨(domain-events.md, persistence.md)"))
        } else {
            result.add(passFinding("$rel (트랜잭션 경계 미보유 확인)"))
        }
    }

    for (f in collectKtFiles(root)) {
        if (!f.nameWithoutExtension.endsWith("RepositoryImpl")) continue
        val content = f.readText()
        if (!content.contains("Outbox")) continue
        found = true
        val rel = f.relTo(root)
        if (content.contains("@Transactional")) {
            result.add(passFinding("$rel (Repository.save() 트랜잭션 경계 확인)"))
        } else {
            result.add(failFinding(rel, "Outbox를 저장하는 Repository 구현체에 @Transactional이 없음 — Aggregate 저장과 Outbox 적재가 원자적이지 않을 수 있음"))
        }
    }

    if (!found) result.add(skipFinding("Command Service/Outbox 연동 Repository 구현체 없음"))
    return result
}

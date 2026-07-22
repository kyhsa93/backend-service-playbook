package harness.rules

import harness.*
import java.io.File

/** [11] Transaction boundary — must be absent from the Command Service and present on Repository.save() */
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
            result.add(failFinding(rel, "a Command Service must not have @Transactional — the transaction boundary is delegated to Repository.save()(domain-events.md, persistence.md)"))
        } else {
            result.add(passFinding("$rel (confirmed no transaction boundary)"))
        }
    }

    for (f in collectKtFiles(root)) {
        if (!f.nameWithoutExtension.endsWith("RepositoryImpl")) continue
        val content = f.readText()
        if (!content.contains("Outbox")) continue
        found = true
        val rel = f.relTo(root)
        if (content.contains("@Transactional")) {
            result.add(passFinding("$rel (confirmed Repository.save() transaction boundary)"))
        } else {
            result.add(failFinding(rel, "the Repository implementation persisting the Outbox has no @Transactional — Aggregate persistence and Outbox writes may not be atomic"))
        }
    }

    if (!found) result.add(skipFinding("no Command Service/Outbox-integrated Repository implementation"))
    return result
}

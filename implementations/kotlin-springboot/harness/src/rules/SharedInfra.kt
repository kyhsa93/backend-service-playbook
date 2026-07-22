package harness.rules

import harness.*
import java.io.File

/**
 * [8] shared-infra: outbox / task-queue
 *
 * The outbox trigger is determined by "is there code that actually references OutboxWriter" — to
 * avoid being tripped up by an unrelated file whose name happens to contain "Outbox"(a past bug: the
 * actual files were already entirely inside outbox/, so the "find a file outside it" condition was
 * always false, meaning it only ever SKIPped and never substantively verified the outbox package).
 *
 * `OutboxRelay`(synchronous drain) is not present — Outbox → queue publishing is handled by
 * `OutboxPoller` and queue → handler routing by `OutboxConsumer`. This rule's trigger and
 * required-file checks target that current design(requiring OutboxRelay.kt would always fail now).
 */
fun checkSharedInfra(rootPath: String): RuleResult {
    val root = File(rootPath)
    val result = RuleResult("shared-infra")
    checkOutboxPattern(root).forEach { result.add(it) }
    checkTaskQueuePattern(root).forEach { result.add(it) }
    return result
}

private fun checkOutboxPattern(root: File): List<Finding> {
    val usesOutboxWriter = collectKtFiles(root).any { it.readText().contains("OutboxWriter") }
    if (!usesOutboxWriter) return listOf(skipFinding("no outbox pattern"))

    val outboxDirs = root.walkTopDown()
        .onEnter { it.name !in setOf("test", "build") }
        .filter { it.isDirectory && it.name == "outbox" }
        .toList()

    if (outboxDirs.isEmpty()) {
        return listOf(failFinding("outbox package", "references OutboxWriter but there is no outbox/ package"))
    }

    val hasWriter = outboxDirs.any { File(it, "OutboxWriter.kt").isFile }
    val hasPoller = outboxDirs.any { File(it, "OutboxPoller.kt").isFile }
    val hasConsumer = outboxDirs.any { File(it, "OutboxConsumer.kt").isFile }
    return if (hasWriter && hasPoller && hasConsumer) {
        listOf(passFinding("outbox package (confirmed OutboxWriter/OutboxPoller/OutboxConsumer implementations)"))
    } else {
        listOf(
            failFinding(
                "outbox package",
                "the outbox/ package exists but OutboxWriter.kt/OutboxPoller.kt/OutboxConsumer.kt could not be found",
            ),
        )
    }
}

private fun checkTaskQueuePattern(root: File): List<Finding> {
    val hasTaskFile = root.walkTopDown()
        .onEnter { it.name !in setOf("test", "build") }
        .any { it.isFile && it.extension == "kt" && it.name.contains("TaskQueue") }

    if (!hasTaskFile) return listOf(skipFinding("no task-queue pattern"))

    val hasTaskDir = root.walkTopDown()
        .onEnter { it.name !in setOf("test", "build") }
        .any { it.isDirectory && (it.name == "task-queue" || it.name == "taskqueue") }

    return if (hasTaskDir) {
        listOf(passFinding("task-queue package"))
    } else {
        listOf(failFinding("task-queue package", "a TaskQueue file exists but there is no task-queue/ package"))
    }
}

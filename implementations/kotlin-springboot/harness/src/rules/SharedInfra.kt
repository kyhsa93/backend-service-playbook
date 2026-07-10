package harness.rules

import harness.*
import java.io.File

/**
 * [8] shared-infra: outbox·task-queue
 *
 * outbox 트리거는 "OutboxRelay를 실제로 참조하는 코드가 있는가"로 판단한다 — 파일명에
 * 우연히 "Outbox"가 들어간 무관한 파일에 낚이지 않기 위함이다(과거 버그: 실제 파일들이
 * 이미 전부 outbox/ 안에 있어서 "밖에 있는 파일 찾기" 조건이 항상 거짓이 되어 SKIP만
 * 하고 outbox 패키지를 실질적으로 검증한 적이 없었다).
 */
fun checkSharedInfra(rootPath: String): RuleResult {
    val root = File(rootPath)
    val result = RuleResult("shared-infra")
    checkOutboxPattern(root).forEach { result.add(it) }
    checkTaskQueuePattern(root).forEach { result.add(it) }
    return result
}

private fun checkOutboxPattern(root: File): List<Finding> {
    val usesOutboxRelay = collectKtFiles(root).any { it.readText().contains("OutboxRelay") }
    if (!usesOutboxRelay) return listOf(skipFinding("outbox 패턴 없음"))

    val outboxDirs = root.walkTopDown()
        .onEnter { it.name !in setOf("test", "build") }
        .filter { it.isDirectory && it.name == "outbox" }
        .toList()

    if (outboxDirs.isEmpty()) {
        return listOf(failFinding("outbox 패키지", "OutboxRelay를 참조하지만 outbox/ 패키지가 없음"))
    }

    val hasWriter = outboxDirs.any { File(it, "OutboxWriter.kt").isFile }
    val hasRelay = outboxDirs.any { File(it, "OutboxRelay.kt").isFile }
    return if (hasWriter && hasRelay) {
        listOf(passFinding("outbox 패키지 (OutboxWriter/OutboxRelay 구현 확인)"))
    } else {
        listOf(failFinding("outbox 패키지", "outbox/ 패키지는 있으나 OutboxWriter.kt/OutboxRelay.kt를 찾을 수 없음"))
    }
}

private fun checkTaskQueuePattern(root: File): List<Finding> {
    val hasTaskFile = root.walkTopDown()
        .onEnter { it.name !in setOf("test", "build") }
        .any { it.isFile && it.extension == "kt" && it.name.contains("TaskQueue") }

    if (!hasTaskFile) return listOf(skipFinding("task-queue 패턴 없음"))

    val hasTaskDir = root.walkTopDown()
        .onEnter { it.name !in setOf("test", "build") }
        .any { it.isDirectory && (it.name == "task-queue" || it.name == "taskqueue") }

    return if (hasTaskDir) {
        listOf(passFinding("task-queue 패키지"))
    } else {
        listOf(failFinding("task-queue 패키지", "TaskQueue 파일이 있으나 task-queue/ 패키지 없음"))
    }
}

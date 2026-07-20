package harness.rules

import harness.*
import java.io.File

/**
 * [8] shared-infra: outbox·task-queue
 *
 * outbox 트리거는 "OutboxWriter를 실제로 참조하는 코드가 있는가"로 판단한다 — 파일명에
 * 우연히 "Outbox"가 들어간 무관한 파일에 낚이지 않기 위함이다(과거 버그: 실제 파일들이
 * 이미 전부 outbox/ 안에 있어서 "밖에 있는 파일 찾기" 조건이 항상 거짓이 되어 SKIP만
 * 하고 outbox 패키지를 실질적으로 검증한 적이 없었다).
 *
 * 2026-07 async 전환 이후 `OutboxRelay`(동기 드레인)는 삭제되고 `OutboxPoller`(Outbox → SQS
 * 발행)/`OutboxConsumer`(SQS → 핸들러 라우팅)로 대체됐다 — 이 규칙의 트리거·필수 파일 검사도
 * 그에 맞춰 뒤집었다(OutboxRelay.kt를 요구하면 지금은 항상 실패한다).
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
    if (!usesOutboxWriter) return listOf(skipFinding("outbox 패턴 없음"))

    val outboxDirs = root.walkTopDown()
        .onEnter { it.name !in setOf("test", "build") }
        .filter { it.isDirectory && it.name == "outbox" }
        .toList()

    if (outboxDirs.isEmpty()) {
        return listOf(failFinding("outbox 패키지", "OutboxWriter를 참조하지만 outbox/ 패키지가 없음"))
    }

    val hasWriter = outboxDirs.any { File(it, "OutboxWriter.kt").isFile }
    val hasPoller = outboxDirs.any { File(it, "OutboxPoller.kt").isFile }
    val hasConsumer = outboxDirs.any { File(it, "OutboxConsumer.kt").isFile }
    return if (hasWriter && hasPoller && hasConsumer) {
        listOf(passFinding("outbox 패키지 (OutboxWriter/OutboxPoller/OutboxConsumer 구현 확인)"))
    } else {
        listOf(
            failFinding(
                "outbox 패키지",
                "outbox/ 패키지는 있으나 OutboxWriter.kt/OutboxPoller.kt/OutboxConsumer.kt를 찾을 수 없음",
            ),
        )
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

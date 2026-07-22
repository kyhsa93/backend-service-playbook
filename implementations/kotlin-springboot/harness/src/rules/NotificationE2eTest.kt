package harness.rules

import harness.*
import java.io.File

/**
 * [13] Checks that NotificationE2ETest exists
 *
 * Other rules exclude the entire test/ directory from scanning(see collectKtFiles) — meaning that if
 * this regression test were deleted outright, no other rule would notice. Since this is the only e2e
 * test that verifies the whole Outbox path, at least its existence is checked separately, without
 * excluding the test/ directory.
 */
fun checkNotificationE2eTest(rootPath: String): RuleResult {
    val root = File(rootPath)
    val result = RuleResult("notification-e2e-test")
    val found = root.walkTopDown()
        .onEnter { it.name !in setOf(".git", "build") }
        .any { it.isFile && it.name == "NotificationE2ETest.kt" && it.pathContains("/test/") }
    if (found) {
        result.add(passFinding("src/test/.../notification/NotificationE2ETest.kt"))
    } else {
        result.add(failFinding("notification/NotificationE2ETest.kt", "there is no e2e test verifying the Outbox notification-sending path"))
    }
    return result
}

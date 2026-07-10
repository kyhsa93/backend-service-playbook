package harness.rules

import harness.*
import java.io.File

/**
 * [13] NotificationE2ETest 존재 확인
 *
 * 다른 규칙들은 test/ 디렉토리 전체를 검사 대상에서 제외한다(collectKtFiles 참고) —
 * 이 회귀 테스트가 통째로 삭제되어도 다른 어떤 규칙도 이를 알아채지 못한다는 뜻이다.
 * Outbox 경로 전체를 검증하는 유일한 e2e 테스트이므로 최소한 존재 여부만은 별도로,
 * test/ 디렉토리를 제외하지 않고 확인한다.
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
        result.add(failFinding("notification/NotificationE2ETest.kt", "Outbox 알림 발송 경로를 검증하는 e2e 테스트가 없음"))
    }
    return result
}

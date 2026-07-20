package harness.rules

import harness.*
import java.io.File

// SLF4J 직접 사용, mu.KotlinLogging(microutils), io.github.oshai.kotlinlogging 세 가지 로깅 API
// 모두를 신호로 잡는다 — 이 저장소는 현재 SLF4J를 쓰지만(observability.md), kotlin-logging 도입도
// 검토 중이라 두 라이브러리 이름 모두 블록리스트에 둔다.
private val LOGGING_SIGNAL = Regex(
    """import\s+org\.slf4j|import\s+mu\.KotlinLogging|import\s+io\.github\.oshai\.kotlinlogging|LoggerFactory\.getLogger|KotlinLogging\.logger""",
)

/**
 * [R8] no-logging-in-domain — domain/ 은 로깅하지 않는다 (observability.md "Domain 레이어에서
 * 로깅하지 않는다"). Aggregate/Domain Service는 프레임워크·인프라 의존이 없어야 한다는 원칙의
 * 연장선 — 실패는 예외로 표현하고, 로깅은 그 예외를 받는 Application/Infrastructure 레이어의 몫이다.
 */
fun checkNoLoggingInDomain(rootPath: String): RuleResult {
    val root = File(rootPath)
    val result = RuleResult("no-logging-in-domain")
    var found = false

    for (f in collectKtFiles(root)) {
        if (!f.pathContains("/domain/")) continue
        found = true
        val rel = f.relTo(root)
        val content = f.readText()
        if (LOGGING_SIGNAL.containsMatchIn(content)) {
            result.add(failFinding(rel, "domain/ 레이어에서 로깅 금지 — SLF4J/kotlin-logging 등 사용 불가 (observability.md)"))
        } else {
            result.add(passFinding("$rel (로깅 미사용)"))
        }
    }

    if (!found) result.add(skipFinding("domain/ Kotlin 파일 없음"))
    return result
}

package harness.rules

import harness.*
import java.io.File

private val ENV_ACCESS = Regex("""System\.getenv\(""")

/**
 * [R6] no-direct-env-access-outside-config — domain/, application/ 은 System.getenv(...)를 직접
 * 호출할 수 없다. 환경 변수는 config/의 @ConfigurationProperties 클래스로만 바인딩하고,
 * infrastructure/에서 그 프로퍼티 객체를 주입받아 사용해야 한다 (config.md).
 */
fun checkNoDirectEnvAccessOutsideConfig(rootPath: String): RuleResult {
    val root = File(rootPath)
    val result = RuleResult("no-direct-env-access-outside-config")
    var found = false

    for (f in collectKtFiles(root)) {
        if (!(f.pathContains("/domain/") || f.pathContains("/application/"))) continue
        found = true
        val rel = f.relTo(root)
        val content = f.readText()
        if (ENV_ACCESS.containsMatchIn(content)) {
            result.add(
                failFinding(
                    rel,
                    "domain/, application/ 에서 System.getenv() 직접 호출 금지 — config/(@ConfigurationProperties) 또는 infrastructure/만 환경 변수에 접근 가능 (config.md)",
                ),
            )
        } else {
            result.add(passFinding("$rel (환경 변수 직접 접근 없음)"))
        }
    }

    if (!found) result.add(skipFinding("domain/, application/ Kotlin 파일 없음"))
    return result
}

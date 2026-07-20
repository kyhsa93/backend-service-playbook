package harness.rules

import harness.*
import java.io.File

private val BLOCK_COMMENT = Regex("""/\*[\s\S]*?\*/""")
private val LINE_COMMENT = Regex("""//[^\n]*""")

private fun stripComments(content: String): String =
    content.replace(BLOCK_COMMENT, "").replace(LINE_COMMENT, "")

// 완전히 빈 catch 블록만 좁게 잡는다(중괄호 사이에 공백/개행 외에 아무것도 없음). 로깅이든
// rethrow든 한 글자라도 있으면 매치되지 않으므로, 이 저장소가 실제로 쓰는
// `catch (e: Exception) { logger.atError()... }`나 `runCatching { }.onFailure { logger.error(...) }`
// 같은 정상 패턴을 오탐할 위험이 없다.
private val EMPTY_CATCH = Regex("""catch\s*\([^)]*\)\s*\{\s*}""")

/**
 * [R10] no-silent-catch — application/, infrastructure/ 에서 예외를 잡고도 로깅도 재던지기도
 * 하지 않는 완전히 빈 catch 블록을 금지한다 (observability.md). 좁은 blocklist(빈 블록만)로 한정해
 * 오탐 위험을 최소화했다 — 넓은 "catch 안에 logger 호출이 없으면 실패" 같은 규칙은
 * runCatching/커스텀 헬퍼 등 이 저장소가 실제로 쓰는 정상 패턴까지 걸릴 위험이 있어 채택하지 않았다.
 */
fun checkNoSilentCatch(rootPath: String): RuleResult {
    val root = File(rootPath)
    val result = RuleResult("no-silent-catch")
    var found = false

    for (f in collectKtFiles(root)) {
        if (!(f.pathContains("/application/") || f.pathContains("/infrastructure/"))) continue
        found = true
        val rel = f.relTo(root)
        val code = stripComments(f.readText())
        if (EMPTY_CATCH.containsMatchIn(code)) {
            result.add(failFinding(rel, "빈 catch 블록 금지 — 로깅하거나 재던지기(rethrow)해야 함 (observability.md)"))
        } else {
            result.add(passFinding("$rel (빈 catch 블록 없음)"))
        }
    }

    if (!found) result.add(skipFinding("application/, infrastructure/ Kotlin 파일 없음"))
    return result
}

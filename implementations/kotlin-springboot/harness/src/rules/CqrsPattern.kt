package harness.rules

import harness.*
import java.io.File

// application/query/ 아래 파일이 쓰기 모델 Repository(AccountRepository 등, *Repository 타입)에
// 의존하면 FAIL. Query Service는 읽기 전용 인터페이스(AccountQuery 등)에만 의존해야 한다
// (cqrs-pattern.md). nestjs harness의 cqrs-pattern evaluator를 Kotlin 관용구로 이식한 것.
private val WRITE_REPOSITORY_REF = Regex("""\b\w*Repository\b""")

// 주석 안의 언급(예: "쓰기 모델 AccountRepository와 분리한다")까지 잡으면 오탐이 되므로,
// 실제 코드 의존만 보도록 라인/블록 주석을 먼저 제거한다.
private val BLOCK_COMMENT = Regex("""/\*[\s\S]*?\*/""")
private val LINE_COMMENT = Regex("""//[^\n]*""")

private fun stripComments(content: String): String =
    content.replace(BLOCK_COMMENT, "").replace(LINE_COMMENT, "")

/** [14] CQRS — application/query/ 는 쓰기 모델 Repository에 의존 금지 (읽기 전용 Query 인터페이스만) */
fun checkCqrsPattern(rootPath: String): RuleResult {
    val root = File(rootPath)
    val result = RuleResult("cqrs-pattern")
    var found = false
    for (f in collectKtFiles(root)) {
        if (!f.pathContains("/application/query/")) continue
        found = true
        val rel = f.relTo(root)
        val code = stripComments(f.readText())
        if (WRITE_REPOSITORY_REF.containsMatchIn(code)) {
            result.add(failFinding(rel, "application/query/ 는 쓰기 모델 Repository(*Repository)에 의존 금지 — 읽기 전용 Query 인터페이스(AccountQuery 등)만 사용 (cqrs-pattern.md)"))
        } else {
            result.add(passFinding("$rel (Query가 Repository 미의존)"))
        }
    }
    if (!found) result.add(skipFinding("application/query/ Kotlin 파일 없음"))
    return result
}

package harness.rules

import harness.*
import java.io.File

private val BLOCK_COMMENT = Regex("""/\*[\s\S]*?\*/""")
private val LINE_COMMENT = Regex("""//[^\n]*""")

private fun stripComments(content: String): String =
    content.replace(BLOCK_COMMENT, "").replace(LINE_COMMENT, "")

// throw 직후 바로 문자열 리터럴 인자를 받는 제네릭 예외 생성만 잡는다 — throw SomeException(cause)처럼
// 원인 예외를 감싸는 rethrow, 또는 커스텀 sealed 하위 클래스(AccountNotFoundException 등)의 이름이
// 우연히 "Exception"으로 끝나는 경우는 매치되지 않는다(\b 경계 + 정확한 클래스명만 나열).
private val GENERIC_EXCEPTION_WITH_LITERAL =
    Regex("""\bthrow\s+(RuntimeException|IllegalStateException|IllegalArgumentException|Exception|Error)\s*\(\s*["']""")

/**
 * [S4] typed-errors-only — root 절대 규칙("에러는 enum으로 타입화 — free-form 문자열 금지",
 * AGENTS.md/error-handling.md)의 domain/application 범위 검사. 이 저장소의 관용구는 `sealed class
 * *Exception` 계층(AccountException, CardException 등)이므로, domain/application에서
 * `RuntimeException("...")`/`IllegalStateException("...")` 같은 제네릭 예외를 문자열 리터럴과 함께
 * 직접 던지면 실패 — 대신 sealed 계층의 구체적인 하위 타입을 throw해야 한다.
 *
 * outbox/(공유 인프라, infrastructure 성격)의 메시지 파싱 실패 같은 "비즈니스 규칙 위반이 아닌 기술적
 * 오류"는 root error-handling.md가 domain/application에만 강제하는 이 규칙의 대상이 아니다 — 실제로
 * outbox/OutboxConsumer.kt가 IllegalStateException을 쓰지만 domain/, application/ 밖이라 대상에서
 * 제외된다(의도된 스코프, 실제 코드로 확인).
 */
fun checkTypedErrorsOnly(rootPath: String): RuleResult {
    val root = File(rootPath)
    val result = RuleResult("typed-errors-only")
    var found = false

    for (f in collectKtFiles(root)) {
        if (!(f.pathContains("/domain/") || f.pathContains("/application/"))) continue
        found = true
        val rel = f.relTo(root)
        val code = stripComments(f.readText())
        val violation = GENERIC_EXCEPTION_WITH_LITERAL.find(code)
        if (violation != null) {
            result.add(
                failFinding(
                    rel,
                    "domain/, application/ 에서 '${violation.groupValues[1]}(\"...\")' 같은 free-form 문자열 예외 금지 — " +
                        "sealed class 예외 계층의 enum(code) 매핑 하위 타입만 throw 해야 함 (error-handling.md)",
                ),
            )
        } else {
            result.add(passFinding("$rel (타입화된 예외만 사용)"))
        }
    }

    if (!found) result.add(skipFinding("domain/, application/ Kotlin 파일 없음"))
    return result
}

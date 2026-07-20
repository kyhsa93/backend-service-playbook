package harness.rules

import harness.*
import java.io.File

// 주석 안의 언급까지 잡으면 오탐이 되므로, 실제 import 선언만 보도록 라인/블록 주석을 먼저 제거한다.
private val BLOCK_COMMENT = Regex("""/\*[\s\S]*?\*/""")
private val LINE_COMMENT = Regex("""//[^\n]*""")

private fun stripComments(content: String): String =
    content.replace(BLOCK_COMMENT, "").replace(LINE_COMMENT, "")

// 도메인 이름을 하드코딩하지 않는 구조적(경로 기반) 검사 — com.example.accountservice.<아무 BC>.
// (application|infrastructure|interfaces) 형태의 import는 그 BC가 domain/ 자신이 속한 BC든
// 다른(형제) BC든 전부 금지다. domain-purity 규칙(프레임워크 애노테이션/JPA import 블록리스트)보다
// 넓은 범위 — 어떤 상위 레이어 코드도 domain/이 몰라야 한다는 의존 방향 자체를 강제한다.
private val FORBIDDEN_LAYER_IMPORT =
    Regex("""^import\s+com\.example\.accountservice\.\w+\.(application|infrastructure|interfaces)\b""", RegexOption.MULTILINE)

/**
 * [R1] domain-layer-isolation — domain/ 파일은 (자신이 속한 BC든 형제 BC든) application/,
 * infrastructure/, interfaces/ 패키지를 import할 수 없다 (layer-architecture.md).
 */
fun checkDomainLayerIsolation(rootPath: String): RuleResult {
    val root = File(rootPath)
    val result = RuleResult("domain-layer-isolation")
    var found = false

    for (f in collectKtFiles(root)) {
        if (!f.pathContains("/domain/")) continue
        found = true
        val rel = f.relTo(root)
        val code = stripComments(f.readText())
        val violation = FORBIDDEN_LAYER_IMPORT.find(code)
        if (violation != null) {
            result.add(
                failFinding(
                    rel,
                    "domain/ 은 application/·infrastructure/·interfaces/(자신 또는 다른 도메인 포함) import 금지 — '${violation.value.trim()}' (layer-architecture.md)",
                ),
            )
        } else {
            result.add(passFinding("$rel (domain 레이어 격리)"))
        }
    }

    if (!found) result.add(skipFinding("domain/ Kotlin 파일 없음"))
    return result
}

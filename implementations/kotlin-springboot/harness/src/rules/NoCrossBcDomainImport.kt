package harness.rules

import harness.*
import java.io.File

private val BLOCK_COMMENT = Regex("""/\*[\s\S]*?\*/""")
private val LINE_COMMENT = Regex("""//[^\n]*""")

private fun stripComments(content: String): String =
    content.replace(BLOCK_COMMENT, "").replace(LINE_COMMENT, "")

// import com.example.accountservice.<다른 BC>.domain.<Name> — "domain" 세그먼트가 리터럴로
// 뒤따르는 import만 잡는다. common.generateId처럼 domain/이 아닌 공용 유틸 import는 애초에
// ".domain." 세그먼트가 없어 매치되지 않으므로 오탐 위험이 없다(실제 코드로 확인함).
private val DOMAIN_IMPORT =
    Regex("""^import\s+com\.example\.accountservice\.(\w+)\.domain\.(\w+)\b""", RegexOption.MULTILINE)

/**
 * no-cross-bc-domain-import — `<bc>/domain/` 안의 Kotlin 파일은 다른 BC의 domain/ 패키지를 직접
 * import할 수 없다 — root tactical-ddd.md "다른 Aggregate는 ID 참조만 허용한다(객체 참조 금지)"는
 * 같은 BC 안(no-cross-aggregate-reference, payment/domain의 Payment↔Refund)뿐 아니라 서로
 * 다른 BC 사이에도 적용된다. domain-layer-isolation(R1)은 domain/이 application/·infrastructure/·
 * interfaces/(상위 레이어)를 참조하지 못하게 막을 뿐 형제 BC의 domain/끼리의 직접 참조는 막지
 * 않으므로, 이 규칙이 그 빈틈을 닫는다. 크로스 BC 조회가 필요하면 Domain Service가 여러 Aggregate를
 * 함수 파라미터로 받는 방식(domain-service.md `RefundEligibilityService`)이나 ID 참조만 써야 한다.
 */
fun checkNoCrossBcDomainImport(rootPath: String): RuleResult {
    val root = File(rootPath)
    val result = RuleResult("no-cross-bc-domain-import")
    var found = false

    for (f in collectKtFiles(root)) {
        if (!f.pathContains("/domain/")) continue
        val ownBc = f.segmentBefore("domain") ?: continue
        found = true
        val rel = f.relTo(root)
        val code = stripComments(f.readText())

        var fileHasFailure = false
        for (m in DOMAIN_IMPORT.findAll(code)) {
            val importedBc = m.groupValues[1]
            val importedType = m.groupValues[2]
            if (importedBc != ownBc) {
                fileHasFailure = true
                result.add(
                    failFinding(
                        rel,
                        "다른 BC($importedBc)의 domain/$importedType 를 직접 import 금지 — 다른 Aggregate는 ID 참조(<noun>Id: String)만 허용, 여러 Aggregate가 필요하면 Domain Service가 함수 파라미터로 받아야 함 (tactical-ddd.md)",
                    ),
                )
            }
        }
        if (!fileHasFailure) result.add(passFinding("$rel (크로스 BC domain 미참조)"))
    }

    if (!found) result.add(skipFinding("domain/ Kotlin 파일 없음"))
    return result
}

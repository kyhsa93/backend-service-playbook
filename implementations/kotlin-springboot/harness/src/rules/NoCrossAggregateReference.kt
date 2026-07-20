package harness.rules

import harness.*
import java.io.File

private val BLOCK_COMMENT = Regex("""/\*[\s\S]*?\*/""")
private val LINE_COMMENT = Regex("""//[^\n]*""")

private fun stripComments(content: String): String =
    content.replace(BLOCK_COMMENT, "").replace(LINE_COMMENT, "")

// 클래스 최상단 프로퍼티 선언(`var`/`val NAME: Type`)만 잡는다 — Kotlin의 일반 함수 파라미터는
// var/val 키워드를 갖지 않으므로, RefundEligibilityService.evaluate(payment: Payment, refund: Refund)
// 처럼 여러 Aggregate를 함수 파라미터로 받는 정당한 Domain Service 패턴(domain-service.md)은 이
// 줄-앵커 정규식에 걸리지 않는다.
private fun propertyLineRegexFor(typeName: String) =
    Regex("""^\s*(var|val)\s+\w+\s*:\s*(List<)?$typeName\??>?\b""", RegexOption.MULTILINE)

// payment/domain/ 은 Payment·Refund 두 Aggregate가 실제로 공존하는 현재 유일한 케이스다
// (domain-service.md의 RefundEligibilityService 예시). 일반화(모든 domain/ 디렉토리에서 클래스
// 선언을 스캔해 Aggregate 쌍을 자동 추론)를 시도하면 account/domain/의 Account↔Transaction처럼
// "형제 Aggregate"가 아니라 "Aggregate가 자신의 하위 Entity를 보유"하는 정당한 관계까지 같은
// 패턴(`class X private constructor()`)으로 잡혀 오탐하므로, 이 규칙은 실제 위반 사례가 있는
// payment/domain/으로 범위를 좁힌다.
private const val SCOPE_DIR = "/payment/domain/"

/**
 * [R5] no-cross-aggregate-reference — payment/domain/의 Payment는 Refund를, Refund는 Payment를
 * 필드로 직접 참조할 수 없다. `paymentId: String` 같은 ID 참조만 허용한다 (domain-service.md).
 */
fun checkNoCrossAggregateReference(rootPath: String): RuleResult {
    val root = File(rootPath)
    val result = RuleResult("no-cross-aggregate-reference")

    val paymentFile = collectKtFiles(root).find { it.pathContains(SCOPE_DIR) && it.name == "Payment.kt" }
    val refundFile = collectKtFiles(root).find { it.pathContains(SCOPE_DIR) && it.name == "Refund.kt" }

    if (paymentFile == null || refundFile == null) {
        result.add(skipFinding("payment/domain/Payment.kt 또는 Refund.kt 없음"))
        return result
    }

    checkNoFieldReference(paymentFile, root, "Refund", result)
    checkNoFieldReference(refundFile, root, "Payment", result)
    return result
}

private fun checkNoFieldReference(file: File, root: File, forbiddenType: String, result: RuleResult) {
    val code = stripComments(file.readText())
    val rel = file.relTo(root)
    if (propertyLineRegexFor(forbiddenType).containsMatchIn(code)) {
        result.add(
            failFinding(
                rel,
                "다른 Aggregate($forbiddenType)를 필드로 직접 참조 금지 — ${forbiddenType.replaceFirstChar { it.lowercase() }}Id: String 같은 ID 참조만 허용 (domain-service.md)",
            ),
        )
    } else {
        result.add(passFinding("$rel ($forbiddenType 필드 미참조)"))
    }
}

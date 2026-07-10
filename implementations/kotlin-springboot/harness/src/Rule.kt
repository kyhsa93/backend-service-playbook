package harness

/** Finding의 종류 — 통과, 실패, 또는 "적용 대상 아님"을 나타내는 skip. */
enum class Kind { PASS, FAIL, SKIP }

/** 한 규칙의 섹션 출력 안에 들어가는 항목 하나. Skip인 경우 name이 skip 메시지를 담는다. */
data class Finding(val kind: Kind, val name: String, val reason: String = "")

/** 규칙 하나가 반환하는 결과 — 섹션 헤더 + 그 아래 출력할 Finding 목록. */
class RuleResult(val section: String) {
    val findings: MutableList<Finding> = mutableListOf()

    fun add(finding: Finding) {
        findings.add(finding)
    }

    fun count(kind: Kind): Int = findings.count { it.kind == kind }
}

fun passFinding(name: String): Finding = Finding(Kind.PASS, name)
fun failFinding(name: String, reason: String): Finding = Finding(Kind.FAIL, name, reason)
fun skipFinding(message: String): Finding = Finding(Kind.SKIP, message)

/** 규칙 함수 시그니처 — projectRoot 절대경로를 받아 RuleResult를 반환한다. */
typealias Rule = (String) -> RuleResult

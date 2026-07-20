package harness.rules

import harness.*
import java.io.File

private val BLOCK_COMMENT = Regex("""/\*[\s\S]*?\*/""")
private val LINE_COMMENT = Regex("""//[^\n]*""")

private fun stripComments(content: String): String =
    content.replace(BLOCK_COMMENT, "").replace(LINE_COMMENT, "")

// import com.example.accountservice.<도메인>.domain.<Name>Repository 또는 <Name>Query
private val REPOSITORY_OR_QUERY_IMPORT =
    Regex("""^import\s+com\.example\.accountservice\.(\w+)\.domain\.(\w*(?:Repository|Query))\b""", RegexOption.MULTILINE)

/**
 * [R7] no-cross-bc-repository-in-application — application/ 파일은 자신이 속한 도메인이 아닌
 * 다른 도메인의 domain/ 안 *Repository, *Query 인터페이스를 직접 import할 수 없다. 크로스 도메인
 * 읽기는 Adapter(호출하는 쪽의 application/adapter/ 인터페이스 + infrastructure/ 의 *AdapterImpl
 * 구현체, 예: card/application/adapter/AccountAdapter + card/infrastructure/AccountAdapterImpl)를
 * 거쳐야 한다 (cross-domain-communication.md). 같은 도메인 안의 import(예: AccountRepository를 쓰는
 * account/application/의 Command Service)는 정상 패턴이므로 대상이 아니다.
 */
fun checkNoCrossBcRepositoryInApplication(rootPath: String): RuleResult {
    val root = File(rootPath)
    val result = RuleResult("no-cross-bc-repository-in-application")
    var found = false

    for (f in collectKtFiles(root)) {
        if (!f.pathContains("/application/")) continue
        val ownDomain = f.segmentBefore("application") ?: continue
        found = true
        val rel = f.relTo(root)
        val code = stripComments(f.readText())

        var fileHasFailure = false
        for (m in REPOSITORY_OR_QUERY_IMPORT.findAll(code)) {
            val importedDomain = m.groupValues[1]
            val importedType = m.groupValues[2]
            if (importedDomain != ownDomain) {
                fileHasFailure = true
                result.add(
                    failFinding(
                        rel,
                        "다른 도메인($importedDomain)의 domain/$importedType 를 application/에서 직접 import 금지 — Adapter(application/adapter/ + infrastructure/*AdapterImpl)를 거쳐야 함 (cross-domain-communication.md)",
                    ),
                )
            }
        }
        if (!fileHasFailure) result.add(passFinding("$rel (크로스 도메인 Repository/Query 직접 참조 없음)"))
    }

    if (!found) result.add(skipFinding("application/ Kotlin 파일 없음"))
    return result
}

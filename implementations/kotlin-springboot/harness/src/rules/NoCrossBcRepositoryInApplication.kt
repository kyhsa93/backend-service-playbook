package harness.rules

import harness.*
import java.io.File

private val BLOCK_COMMENT = Regex("""/\*[\s\S]*?\*/""")
private val LINE_COMMENT = Regex("""//[^\n]*""")

private fun stripComments(content: String): String =
    content.replace(BLOCK_COMMENT, "").replace(LINE_COMMENT, "")

// import com.example.accountservice.<domain>.domain.<Name>Repository or <Name>Query
private val REPOSITORY_OR_QUERY_IMPORT =
    Regex("""^import\s+com\.example\.accountservice\.(\w+)\.domain\.(\w*(?:Repository|Query))\b""", RegexOption.MULTILINE)

/**
 * [R7] no-cross-bc-repository-in-application — a file in application/ may not directly import a
 * *Repository, *Query interface under domain/ from a domain other than its own. Cross-domain reads
 * must go through an Adapter(the calling side's application/adapter/ interface +
 * infrastructure/'s *AdapterImpl implementation, e.g. card/application/adapter/AccountAdapter +
 * card/infrastructure/AccountAdapterImpl) (cross-domain-communication.md). An import within the same
 * domain(e.g. account/application/'s Command Service using AccountRepository) is a normal pattern and
 * not targeted.
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
                        "may not directly import domain/$importedType from another domain($importedDomain) inside application/ — must go through an Adapter(application/adapter/ + infrastructure/*AdapterImpl) (cross-domain-communication.md)",
                    ),
                )
            }
        }
        if (!fileHasFailure) result.add(passFinding("$rel (no direct cross-domain Repository/Query reference)"))
    }

    if (!found) result.add(skipFinding("no application/ Kotlin files"))
    return result
}

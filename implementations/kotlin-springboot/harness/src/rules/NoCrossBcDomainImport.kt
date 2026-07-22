package harness.rules

import harness.*
import java.io.File

private val BLOCK_COMMENT = Regex("""/\*[\s\S]*?\*/""")
private val LINE_COMMENT = Regex("""//[^\n]*""")

private fun stripComments(content: String): String =
    content.replace(BLOCK_COMMENT, "").replace(LINE_COMMENT, "")

// import com.example.accountservice.<another BC>.domain.<Name> — only catches imports literally
// followed by a "domain" segment. An import of a shared, non-domain/ utility like common.generateId
// has no ".domain." segment to begin with, so it can't match — no false-positive risk(confirmed
// against the actual code).
private val DOMAIN_IMPORT =
    Regex("""^import\s+com\.example\.accountservice\.(\w+)\.domain\.(\w+)\b""", RegexOption.MULTILINE)

/**
 * no-cross-bc-domain-import — a Kotlin file inside `<bc>/domain/` may not directly import another
 * BC's domain/ package — the root tactical-ddd.md principle "other Aggregates may only be referenced
 * by ID(no object references)" applies not only within the same BC(no-cross-aggregate-reference, the
 * Payment↔Refund case in payment/domain) but also between different BCs.
 * domain-layer-isolation(R1) only blocks domain/ from referencing application/·infrastructure/·
 * interfaces/(higher layers) — it doesn't block sibling BCs' domain/ from directly referencing each
 * other, so this rule closes that gap. Where a cross-BC lookup is needed, only a Domain Service
 * receiving multiple Aggregates as function parameters(domain-service.md
 * `RefundEligibilityService`) or an ID reference may be used.
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
                        "may not directly import domain/$importedType from another BC($importedBc) — other Aggregates may only be referenced by ID(<noun>Id: String); if multiple Aggregates are needed, a Domain Service must receive them as function parameters (tactical-ddd.md)",
                    ),
                )
            }
        }
        if (!fileHasFailure) result.add(passFinding("$rel (no cross-BC domain reference)"))
    }

    if (!found) result.add(skipFinding("no domain/ Kotlin files"))
    return result
}

package harness.rules

import harness.*
import java.io.File

// Catching a mention inside a comment would be a false positive, so line/block comments are stripped
// first to look only at real import declarations.
private val BLOCK_COMMENT = Regex("""/\*[\s\S]*?\*/""")
private val LINE_COMMENT = Regex("""//[^\n]*""")

private fun stripComments(content: String): String =
    content.replace(BLOCK_COMMENT, "").replace(LINE_COMMENT, "")

// A structural(path-based) check that doesn't hardcode domain names — an import of the form
// com.example.accountservice.<any BC>.(application|infrastructure|interfaces) is forbidden regardless
// of whether that BC is domain/'s own BC or a different(sibling) one. Broader in scope than the
// domain-purity rule(a blocklist of framework annotations/JPA imports) — this enforces the dependency
// direction itself, that domain/ must know about no higher-layer code at all.
private val FORBIDDEN_LAYER_IMPORT =
    Regex("""^import\s+com\.example\.accountservice\.\w+\.(application|infrastructure|interfaces)\b""", RegexOption.MULTILINE)

/**
 * [R1] domain-layer-isolation — a domain/ file may not import the application/, infrastructure/,
 * interfaces/ package(whether its own BC or a sibling BC) (layer-architecture.md).
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
                    "domain/ may not import application/·infrastructure/·interfaces/(including its own or another domain) — '${violation.value.trim()}' (layer-architecture.md)",
                ),
            )
        } else {
            result.add(passFinding("$rel (domain layer isolated)"))
        }
    }

    if (!found) result.add(skipFinding("no domain/ Kotlin files"))
    return result
}

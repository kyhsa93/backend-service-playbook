package harness.rules

import harness.*
import java.io.File

private val DDL_AUTO_LINE = Regex("""ddl-auto:\s*['"]?([\w-]+)['"]?""")
private val ALLOWED_VALUES = setOf("validate", "none")

// application.yml isn't Kotlin source, so instead of collectKtFiles this directly looks for
// application*.yml(.yaml) under src/main/resources/. src/test/resources is out of scope — a
// @DynamicPropertySource create-drop that rebuilds the schema for each Testcontainers container falls
// within the root's explicitly stated "dev/test only" allowance, not production config(persistence.md).
private fun collectApplicationYmlFiles(root: File): List<File> {
    val resourcesDir = File(root, "src/main/resources")
    if (!resourcesDir.exists()) return emptyList()
    return resourcesDir.walkTopDown()
        .filter { it.isFile && it.name.startsWith("application") && (it.extension == "yml" || it.extension == "yaml") }
        .sortedBy { it.path }
        .toList()
}

/**
 * no-orm-autosync-in-prod-config — if the spring.jpa.hibernate.ddl-auto value is specified in
 * src/main/resources/application*.yml(the base plus any per-profile override such as
 * `application-prod.yml` — everything actually deployed to production), it must be either validate
 * or none — update/create/create-drop are forbidden because they can implicitly change the schema at
 * runtime and corrupt the production DB. Schema changes must be made only through Flyway migration
 * files(persistence.md). A file where the ddl-auto key itself is absent(a profile that doesn't
 * override the value at all) is treated as passing.
 */
fun checkNoOrmAutosyncInProdConfig(rootPath: String): RuleResult {
    val root = File(rootPath)
    val result = RuleResult("no-orm-autosync-in-prod-config")
    val files = collectApplicationYmlFiles(root)

    if (files.isEmpty()) {
        result.add(skipFinding("no src/main/resources/application*.yml"))
        return result
    }

    for (f in files) {
        val rel = f.relTo(root)
        val match = DDL_AUTO_LINE.find(f.readText())
        if (match == null) {
            result.add(passFinding("$rel (ddl-auto not set)"))
            continue
        }
        val value = match.groupValues[1]
        if (value in ALLOWED_VALUES) {
            result.add(passFinding("$rel (ddl-auto: $value)"))
        } else {
            result.add(
                failFinding(
                    rel,
                    "spring.jpa.hibernate.ddl-auto: $value is forbidden in production config — only validate or none are allowed, schema changes must go through Flyway migrations only (persistence.md)",
                ),
            )
        }
    }

    return result
}

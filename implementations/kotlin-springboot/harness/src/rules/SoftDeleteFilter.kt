package harness.rules

import harness.*
import java.io.File

private val ENTITY_ANNOTATION = Regex("""@Entity\b""")
private val ENTITY_CLASS_NAME = Regex("""class\s+(\w+)""")
private val DELETED_AT_FIELD = Regex("""\bvar\s+deletedAt\b""")
private val GLOBAL_SOFT_DELETE_ANNOTATION = Regex("""@SQLRestriction\(|@Where\(""")
private val DELETED_AT_FILTER_IN_QUERY = Regex("""(?i)deletedAt\s+is\s+null|DeletedAtIsNull\b""")

/**
 * [S3] soft-delete-filter — hard delete is forbidden, and if a JPA Entity has a `deletedAt`
 * soft-delete column, the find queries in the Repository implementation that fetches that Entity must
 * apply `deletedAt IS NULL`(or the equivalent `findBy...DeletedAtIsNull` derived query) as a
 * filter(persistence.md) — otherwise deleted rows keep showing up in queries. If the Entity itself has
 * a global filter via `@SQLRestriction`/`@Where`, that alone is sufficient without checking the
 * Repository side.
 *
 * Currently in this repository, only Account(and, per the docs, Transaction) has a soft-delete column
 * — Card/Payment/Refund/Credential have no delete use case at all(and no column either). An Entity
 * with no column has no code path that could commit a hard delete, so it's excluded from scope(to
 * avoid excessive false positives, the same "only check what can actually be triggered" principle as
 * this repository's other rules).
 */
fun checkSoftDeleteFilter(rootPath: String): RuleResult {
    val root = File(rootPath)
    val result = RuleResult("soft-delete-filter")
    val allFiles = collectKtFiles(root)

    val entityFiles = allFiles.filter { ENTITY_ANNOTATION.containsMatchIn(it.readText()) }
    val softDeleteEntities =
        entityFiles.mapNotNull { f ->
            val content = f.readText()
            if (!DELETED_AT_FIELD.containsMatchIn(content)) return@mapNotNull null
            val className = ENTITY_CLASS_NAME.find(content)?.groupValues?.get(1) ?: return@mapNotNull null
            Triple(f, className, content)
        }

    if (softDeleteEntities.isEmpty()) {
        result.add(skipFinding("no JPA Entity with a deletedAt column"))
        return result
    }

    val repositoryImplFiles =
        allFiles.filter { it.name.endsWith("RepositoryImpl.kt") }

    for ((entityFile, className, entityContent) in softDeleteEntities) {
        val rel = entityFile.relTo(root)

        if (GLOBAL_SOFT_DELETE_ANNOTATION.containsMatchIn(entityContent)) {
            result.add(passFinding("$rel ($className, @SQLRestriction/@Where global filter applied)"))
            continue
        }

        val referencingImpls = repositoryImplFiles.filter { it.readText().contains(className) }

        if (referencingImpls.isEmpty()) {
            result.add(skipFinding("$rel ($className) — could not find a *RepositoryImpl.kt referencing this Entity, so the filter location cannot be confirmed"))
            continue
        }

        val hasFilter = referencingImpls.any { DELETED_AT_FILTER_IN_QUERY.containsMatchIn(it.readText()) }

        if (hasFilter) {
            result.add(passFinding("$rel ($className, confirmed deletedAt filter in the referencing RepositoryImpl)"))
        } else {
            val implNames = referencingImpls.joinToString { it.relTo(root) }
            result.add(
                failFinding(
                    rel,
                    "$className has a deletedAt column(soft delete), but the find queries in $implNames that fetch it have " +
                        "no deletedAt IS NULL(or findBy...DeletedAtIsNull) filter — deleted rows keep being returned (persistence.md)",
                ),
            )
        }
    }

    return result
}

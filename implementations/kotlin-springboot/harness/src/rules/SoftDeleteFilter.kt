package harness.rules

import harness.*
import java.io.File

private val ENTITY_ANNOTATION = Regex("""@Entity\b""")
private val ENTITY_CLASS_NAME = Regex("""class\s+(\w+)""")
private val DELETED_AT_FIELD = Regex("""\bvar\s+deletedAt\b""")
private val GLOBAL_SOFT_DELETE_ANNOTATION = Regex("""@SQLRestriction\(|@Where\(""")
private val DELETED_AT_FILTER_IN_QUERY = Regex("""(?i)deletedAt\s+is\s+null|DeletedAtIsNull\b""")

/**
 * [S3] soft-delete-filter — hard delete는 금지이고, `deletedAt` soft-delete 컬럼이 있는 JPA
 * Entity라면 그 Entity를 조회하는 Repository 구현체의 find 쿼리가 `deletedAt IS NULL`(또는
 * 동급의 `findBy...DeletedAtIsNull` derived query)을 필터로 걸어야 한다(persistence.md) — 그렇지
 * 않으면 삭제된 행이 조회에 계속 노출된다. Entity 자체에 `@SQLRestriction`/`@Where`로 전역 필터가
 * 걸려 있으면 Repository 쪽 검사 없이 그것으로 충분하다.
 *
 * 이 저장소는 현재 Account(및 Transaction 문서상 언급)만 soft delete 컬럼을 갖고 Card/Payment/
 * Refund/Credential은 삭제 유스케이스 자체가 없다(컬럼도 없음) — 컬럼이 없는 Entity는 hard delete를
 * 저지를 코드 경로 자체가 없으므로 대상에서 제외한다(과도한 오탐 방지, 이 저장소의 다른 규칙과
 * 동일한 "실제로 트리거되는 것만 검사" 원칙).
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
        result.add(skipFinding("deletedAt 컬럼을 가진 JPA Entity 없음"))
        return result
    }

    val repositoryImplFiles =
        allFiles.filter { it.name.endsWith("RepositoryImpl.kt") }

    for ((entityFile, className, entityContent) in softDeleteEntities) {
        val rel = entityFile.relTo(root)

        if (GLOBAL_SOFT_DELETE_ANNOTATION.containsMatchIn(entityContent)) {
            result.add(passFinding("$rel ($className, @SQLRestriction/@Where 전역 필터 적용됨)"))
            continue
        }

        val referencingImpls = repositoryImplFiles.filter { it.readText().contains(className) }

        if (referencingImpls.isEmpty()) {
            result.add(skipFinding("$rel ($className) — 이 Entity를 참조하는 *RepositoryImpl.kt를 찾을 수 없어 필터 위치를 확인할 수 없음"))
            continue
        }

        val hasFilter = referencingImpls.any { DELETED_AT_FILTER_IN_QUERY.containsMatchIn(it.readText()) }

        if (hasFilter) {
            result.add(passFinding("$rel ($className, 참조하는 RepositoryImpl에서 deletedAt 필터 확인)"))
        } else {
            val implNames = referencingImpls.joinToString { it.relTo(root) }
            result.add(
                failFinding(
                    rel,
                    "$className 은 deletedAt 컬럼(soft delete)이 있지만, 이를 조회하는 $implNames 의 find 쿼리에 " +
                        "deletedAt IS NULL(또는 findBy...DeletedAtIsNull) 필터가 없음 — 삭제된 행이 계속 조회됨 (persistence.md)",
                ),
            )
        }
    }

    return result
}

package harness.rules

import harness.*
import java.io.File

private val DDL_AUTO_LINE = Regex("""ddl-auto:\s*['"]?([\w-]+)['"]?""")
private val ALLOWED_VALUES = setOf("validate", "none")

// application.yml은 Kotlin 소스가 아니므로 collectKtFiles를 쓰지 않고 src/main/resources/ 아래
// application*.yml(.yaml)을 직접 찾는다. src/test/resources는 대상 밖 — @DynamicPropertySource로
// Testcontainers 컨테이너마다 새로 스키마를 만드는 create-drop은 root가 명시한 "개발/테스트 전용"
// 허용 범위이지 프로덕션 설정이 아니다(persistence.md).
private fun collectApplicationYmlFiles(root: File): List<File> {
    val resourcesDir = File(root, "src/main/resources")
    if (!resourcesDir.exists()) return emptyList()
    return resourcesDir.walkTopDown()
        .filter { it.isFile && it.name.startsWith("application") && (it.extension == "yml" || it.extension == "yaml") }
        .sortedBy { it.path }
        .toList()
}

/**
 * no-orm-autosync-in-prod-config — src/main/resources/application*.yml(base + `application-prod.yml`
 * 등 프로파일별 오버라이드 포함, 실제 프로덕션에 배포되는 설정 전부)의
 * spring.jpa.hibernate.ddl-auto 값이 명시되어 있다면 반드시 validate 또는 none이어야 한다 —
 * update/create/create-drop은 스키마를 런타임에 암묵적으로 바꿔 프로덕션 DB를 훼손할 수 있으므로
 * 금지된다. 스키마 변경은 Flyway 마이그레이션 파일로만 이루어져야 한다(persistence.md).
 * ddl-auto 키 자체가 없는 파일(값을 아예 오버라이드하지 않는 프로파일)은 통과로 간주한다.
 */
fun checkNoOrmAutosyncInProdConfig(rootPath: String): RuleResult {
    val root = File(rootPath)
    val result = RuleResult("no-orm-autosync-in-prod-config")
    val files = collectApplicationYmlFiles(root)

    if (files.isEmpty()) {
        result.add(skipFinding("src/main/resources/application*.yml 없음"))
        return result
    }

    for (f in files) {
        val rel = f.relTo(root)
        val match = DDL_AUTO_LINE.find(f.readText())
        if (match == null) {
            result.add(passFinding("$rel (ddl-auto 미설정)"))
            continue
        }
        val value = match.groupValues[1]
        if (value in ALLOWED_VALUES) {
            result.add(passFinding("$rel (ddl-auto: $value)"))
        } else {
            result.add(
                failFinding(
                    rel,
                    "spring.jpa.hibernate.ddl-auto: $value 는 프로덕션 설정에서 금지 — validate 또는 none만 허용, 스키마 변경은 Flyway 마이그레이션으로만 (persistence.md)",
                ),
            )
        }
    }

    return result
}

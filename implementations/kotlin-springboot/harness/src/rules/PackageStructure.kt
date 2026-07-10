package harness.rules

import harness.*
import java.io.File

/** [7] 패키지 구조 검사 (4레이어 + CQRS) */
fun checkPackageStructure(rootPath: String): RuleResult {
    val root = File(rootPath)
    val result = RuleResult("package-structure")

    val domainDirs = root.walkTopDown()
        .onEnter { it.name !in setOf("test", ".git", "build") }
        .filter { it.isDirectory && it.name == "domain" }
        .sortedBy { it.path }
        .toList()

    if (domainDirs.isEmpty()) {
        result.add(skipFinding("domain/ 디렉토리 없음"))
        return result
    }

    for (domainDir in domainDirs) {
        val parent = domainDir.parentFile
        val relParent = parent.relTo(root)
        for (layer in listOf("application", "infrastructure", "interfaces")) {
            val dir = File(parent, layer)
            if (dir.isDirectory) {
                result.add(passFinding("$relParent/$layer/"))
            } else {
                result.add(failFinding("$relParent/$layer/", "디렉토리 없음"))
            }
        }
        for (sub in listOf("command", "query")) {
            val dir = File(parent, "application/$sub")
            if (dir.isDirectory) {
                result.add(passFinding("$relParent/application/$sub/"))
            } else {
                result.add(failFinding("$relParent/application/$sub/", "CQRS 디렉토리 없음"))
            }
        }
    }
    return result
}

package harness.rules

import harness.*
import java.io.File

/** [7] Package structure check (4-layer + CQRS) */
fun checkPackageStructure(rootPath: String): RuleResult {
    val root = File(rootPath)
    val result = RuleResult("package-structure")

    val domainDirs = root.walkTopDown()
        .onEnter { it.name !in setOf("test", ".git", "build") }
        .filter { it.isDirectory && it.name == "domain" }
        .sortedBy { it.path }
        .toList()

    if (domainDirs.isEmpty()) {
        result.add(skipFinding("no domain/ directory"))
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
                result.add(failFinding("$relParent/$layer/", "directory missing"))
            }
        }
        for (sub in listOf("command", "query")) {
            val dir = File(parent, "application/$sub")
            if (dir.isDirectory) {
                result.add(passFinding("$relParent/application/$sub/"))
            } else {
                result.add(failFinding("$relParent/application/$sub/", "CQRS directory missing"))
            }
        }
    }
    return result
}

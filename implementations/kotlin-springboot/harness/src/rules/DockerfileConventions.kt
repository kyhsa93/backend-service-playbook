package harness.rules

import harness.*
import java.io.File

private val BUILD_ARTIFACT_EXCLUDE = Regex("""(?m)^\s*(build/?|\.gradle/?|\*\.jar|target/?|node_modules/?|dist/?)\s*$""")
private val GIT_EXCLUDE = Regex("""(?m)^\s*\.git/?\s*$""")

/**
 * [R11] dockerfile-conventions — parses examples/Dockerfile as plain text to check for (a) a
 * multi-stage build(2+ FROM instructions), (b) the presence of a HEALTHCHECK instruction, (c) the
 * presence of a USER instruction(non-root execution), (d) the presence of a .dockerignore with a
 * reasonable exclusion list(container.md). Since a Dockerfile isn't Kotlin source, unlike other rules
 * this doesn't use collectKtFiles and instead looks directly for the file right under rootPath.
 */
fun checkDockerfileConventions(rootPath: String): RuleResult {
    val root = File(rootPath)
    val result = RuleResult("dockerfile-conventions")

    val dockerfile = File(root, "Dockerfile")
    if (!dockerfile.isFile) {
        result.add(skipFinding("no Dockerfile"))
        return result
    }

    val lines = dockerfile.readLines()

    val fromCount = lines.count { it.trim().startsWith("FROM ", ignoreCase = true) }
    if (fromCount >= 2) {
        result.add(passFinding("Dockerfile (multi-stage build, $fromCount FROM instructions)"))
    } else {
        result.add(failFinding("Dockerfile", "not a multi-stage build — only $fromCount FROM instruction(s), must have 2 or more (container.md)"))
    }

    val hasHealthcheck = lines.any { it.trim().startsWith("HEALTHCHECK", ignoreCase = true) }
    if (hasHealthcheck) {
        result.add(passFinding("Dockerfile (HEALTHCHECK present)"))
    } else {
        result.add(failFinding("Dockerfile", "no HEALTHCHECK instruction (container.md)"))
    }

    val hasUser = lines.any { it.trim().startsWith("USER ", ignoreCase = true) }
    if (hasUser) {
        result.add(passFinding("Dockerfile (non-root USER present)"))
    } else {
        result.add(failFinding("Dockerfile", "no USER instruction — the container runs as root (container.md)"))
    }

    val dockerignore = File(root, ".dockerignore")
    if (!dockerignore.isFile) {
        result.add(failFinding(".dockerignore", "no .dockerignore file (container.md)"))
    } else {
        val content = dockerignore.readText()
        val hasBuildExclude = BUILD_ARTIFACT_EXCLUDE.containsMatchIn(content)
        val hasGitExclude = GIT_EXCLUDE.containsMatchIn(content)
        if (hasBuildExclude && hasGitExclude) {
            result.add(passFinding(".dockerignore (confirmed build-artifact/.git exclusion)"))
        } else {
            result.add(failFinding(".dockerignore", "no exclusion pattern for build artifacts(build/, .gradle/, etc.) or .git (container.md)"))
        }
    }

    return result
}

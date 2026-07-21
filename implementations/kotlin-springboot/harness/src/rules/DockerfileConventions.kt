package harness.rules

import harness.*
import java.io.File

private val BUILD_ARTIFACT_EXCLUDE = Regex("""(?m)^\s*(build/?|\.gradle/?|\*\.jar|target/?|node_modules/?|dist/?)\s*$""")
private val GIT_EXCLUDE = Regex("""(?m)^\s*\.git/?\s*$""")

/**
 * [R11] dockerfile-conventions — examples/Dockerfile을 순수 텍스트로 파싱해 (a) 멀티스테이지
 * 빌드(FROM 2개 이상), (b) HEALTHCHECK 인스트럭션 존재, (c) USER 인스트럭션 존재(non-root
 * 실행), (d) 합리적인 제외 목록을 갖춘 .dockerignore 존재를 확인한다 (container.md).
 * Dockerfile은 Kotlin 소스가 아니므로 다른 규칙과 달리 collectKtFiles를 쓰지 않고 rootPath
 * 바로 아래 파일을 직접 찾는다.
 */
fun checkDockerfileConventions(rootPath: String): RuleResult {
    val root = File(rootPath)
    val result = RuleResult("dockerfile-conventions")

    val dockerfile = File(root, "Dockerfile")
    if (!dockerfile.isFile) {
        result.add(skipFinding("Dockerfile 없음"))
        return result
    }

    val lines = dockerfile.readLines()

    val fromCount = lines.count { it.trim().startsWith("FROM ", ignoreCase = true) }
    if (fromCount >= 2) {
        result.add(passFinding("Dockerfile (멀티스테이지 빌드, FROM ${fromCount}개)"))
    } else {
        result.add(failFinding("Dockerfile", "멀티스테이지 빌드가 아님 — FROM 인스트럭션이 ${fromCount}개뿐, 2개 이상이어야 함 (container.md)"))
    }

    val hasHealthcheck = lines.any { it.trim().startsWith("HEALTHCHECK", ignoreCase = true) }
    if (hasHealthcheck) {
        result.add(passFinding("Dockerfile (HEALTHCHECK 존재)"))
    } else {
        result.add(failFinding("Dockerfile", "HEALTHCHECK 인스트럭션 없음 (container.md)"))
    }

    val hasUser = lines.any { it.trim().startsWith("USER ", ignoreCase = true) }
    if (hasUser) {
        result.add(passFinding("Dockerfile (non-root USER 존재)"))
    } else {
        result.add(failFinding("Dockerfile", "USER 인스트럭션 없음 — 컨테이너가 root로 실행됨 (container.md)"))
    }

    val dockerignore = File(root, ".dockerignore")
    if (!dockerignore.isFile) {
        result.add(failFinding(".dockerignore", ".dockerignore 파일 없음 (container.md)"))
    } else {
        val content = dockerignore.readText()
        val hasBuildExclude = BUILD_ARTIFACT_EXCLUDE.containsMatchIn(content)
        val hasGitExclude = GIT_EXCLUDE.containsMatchIn(content)
        if (hasBuildExclude && hasGitExclude) {
            result.add(passFinding(".dockerignore (빌드 산출물/.git 제외 확인)"))
        } else {
            result.add(failFinding(".dockerignore", "빌드 산출물(build/, .gradle/ 등) 또는 .git 제외 패턴 없음 (container.md)"))
        }
    }

    return result
}

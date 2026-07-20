package harness.rules

import harness.*
import java.io.File

private val USES_RANDOM_UUID = Regex("""UUID\.randomUUID\(\)""")

// generateId()가 하이픈을 제거하는지 — .replace("-", "") / .replace('-', ' ') 계열이나
// Regex("-") 기반 제거를 폭넓게 잡는다. 문자열 결과 형태(따옴표 종류)에 얽매이지 않기 위해
// 첫따옴표 문자만 캡처해 동일 문자로 닫히는지는 보지 않고 느슨하게 매치한다(오탐보다 누락을 더 경계).
private val STRIPS_HYPHEN = Regex("""\.replace\(\s*["']-["']|\.replace\(\s*Regex\(\s*["']-["']|\.replace\(\s*'-'""")

/**
 * [S1] aggregate-id-format — Aggregate ID 생성 유틸(`generateId()`)이 `UUID.randomUUID()`를
 * 그대로 문자열화한 하이픈 포함 UUID를 반환하지 않고, 하이픈을 제거한 32자리 hex 문자열을
 * 반환하는지 확인한다(aggregate-id.md). 프로젝트 전체에서 `GenerateId.kt`라는 특정 파일명을
 * 찾는 단일 파일 검사 — 이 저장소의 ID 생성 유틸은 도메인마다 따로 있지 않고 `common/`에 하나만
 * 있는 공유 유틸이므로(SharedInfra.kt의 OutboxWriter/OutboxPoller 리터럴 이름 검사와 동일한
 * 취지), 경로 기반 구조 검사가 아니라 파일명 기반 검사가 적절하다.
 */
fun checkAggregateIdFormat(rootPath: String): RuleResult {
    val root = File(rootPath)
    val result = RuleResult("aggregate-id-format")

    val candidates = collectKtFiles(root).filter { it.name == "GenerateId.kt" }
    if (candidates.isEmpty()) {
        result.add(skipFinding("GenerateId.kt 없음"))
        return result
    }

    for (f in candidates) {
        val rel = f.relTo(root)
        val content = f.readText()

        if (!USES_RANDOM_UUID.containsMatchIn(content)) {
            // UUID.randomUUID()를 아예 안 쓴다면 이 규칙이 전제하는 "raw UUID 문자열화" 위험이
            // 애초에 없다 — 다른 생성 전략(예: 별도 라이브러리)일 수 있으므로 실패시키지 않는다.
            result.add(skipFinding("$rel — UUID.randomUUID() 미사용, 다른 ID 생성 전략으로 판단해 대상 아님"))
            continue
        }

        if (STRIPS_HYPHEN.containsMatchIn(content)) {
            result.add(passFinding("$rel (하이픈 제거 확인)"))
        } else {
            result.add(
                failFinding(
                    rel,
                    "UUID.randomUUID().toString()을 하이픈 제거 없이 그대로 반환하는 것으로 보임 — " +
                        "32자리 hex 문자열이 아닌 하이픈 포함 UUID가 Aggregate ID로 쓰이게 됨 (aggregate-id.md)",
                ),
            )
        }
    }

    return result
}

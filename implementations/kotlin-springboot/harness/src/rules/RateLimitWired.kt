package harness.rules

import harness.*
import java.io.File

private val BLOCK_COMMENT = Regex("""/\*[\s\S]*?\*/""")
private val LINE_COMMENT = Regex("""//[^\n]*""")

private fun stripComments(content: String): String =
    content.replace(BLOCK_COMMENT, "").replace(LINE_COMMENT, "")

// RateLimiterRegistry/@RateLimiter/RateLimiter 타입 전부를 잡기 위해 뒤쪽 단어 경계(\b)는 요구하지
// 않는다 — "RateLimiterRegistry"처럼 "RateLimiter" 바로 뒤에 다른 단어 문자가 이어지는 식별자가
// 실제 코드의 지배적인 형태라 \bRateLimiter\b(양쪽 경계)로는 아무것도 못 잡는다(실제로 겪은 버그).
private val RATE_LIMITER_REFERENCE = Regex("""\bRateLimiter""")
private val CLASS_NAME = Regex("""class\s+(\w+)""")
private val ACQUIRE_CALL = Regex("""\.acquirePermission\(|\.tryAcquirePermission\(""")
private val SPRING_BEAN_ANNOTATION = Regex("""@(Component|Service|Configuration|Bean)\b""")
private val EXPLICIT_FILTER_REGISTRATION = Regex("""FilterRegistrationBean|addFilterBefore|addFilterAfter|addFilter\b""")

/**
 * [S5] rate-limit-wired — Resilience4j `RateLimiter`를 참조하는 `OncePerRequestFilter`(또는
 * Servlet `Filter`) 클래스가 실제로 Spring 컨테이너/필터 체인에 등록되어 있는지 확인한다
 * (rate-limiting.md). `@Component` 등 빈 등록 애노테이션도 없고 `WebConfig.kt`/`SecurityConfig.kt`
 * 류의 `FilterRegistrationBean`/`addFilterBefore` 명시 등록도 없다면, 클래스는 컴파일은 되지만 어떤
 * 요청도 실제로 거치지 않는 죽은 코드다. 또한 RateLimiter를 참조만 하고 `acquirePermission()` 같은
 * 실제 제한 로직 호출이 없는 경우(정의만 되어 있고 실질적으로 아무것도 안 거르는 스텁)도 잡는다.
 */
fun checkRateLimitWired(rootPath: String): RuleResult {
    val root = File(rootPath)
    val result = RuleResult("rate-limit-wired")
    val allFiles = collectKtFiles(root)
    val contents = allFiles.associateWith { stripComments(it.readText()) }

    val usesRateLimiter = contents.values.any { RATE_LIMITER_REFERENCE.containsMatchIn(it) }
    if (!usesRateLimiter) {
        result.add(skipFinding("RateLimiter(Resilience4j) 사용 없음"))
        return result
    }

    val filterFiles =
        allFiles.filter { f ->
            val content = contents.getValue(f)
            content.contains("OncePerRequestFilter") && RATE_LIMITER_REFERENCE.containsMatchIn(content)
        }

    if (filterFiles.isEmpty()) {
        result.add(skipFinding("RateLimiter를 사용하는 Filter 클래스 없음 (애노테이션 기반 적용 등 다른 방식일 수 있음)"))
        return result
    }

    for (f in filterFiles) {
        val rel = f.relTo(root)
        val content = contents.getValue(f)
        val className = CLASS_NAME.find(content)?.groupValues?.get(1) ?: rel

        if (!ACQUIRE_CALL.containsMatchIn(content)) {
            result.add(
                failFinding(
                    rel,
                    "$className 이 RateLimiter를 참조하지만 acquirePermission() 등 실제 제한 로직 호출이 없음 — " +
                        "정의만 되어 있고 실질적으로 요청을 제한하지 않는 스텁으로 의심됨 (rate-limiting.md)",
                ),
            )
            continue
        }

        val selfRegistered = SPRING_BEAN_ANNOTATION.containsMatchIn(content)
        val explicitlyRegistered =
            contents.values.any { otherContent ->
                EXPLICIT_FILTER_REGISTRATION.containsMatchIn(otherContent) && otherContent.contains(className)
            }

        if (selfRegistered || explicitlyRegistered) {
            result.add(passFinding("$rel ($className, 요청 파이프라인에 등록됨)"))
        } else {
            result.add(
                failFinding(
                    rel,
                    "$className 이 RateLimiter로 요청을 제한하는 로직을 갖고 있지만 @Component 등 빈 등록 애노테이션도, " +
                        "FilterRegistrationBean/addFilterBefore 명시 등록도 없어 실제 필터 체인에 적용되지 않는 죽은 코드로 의심됨 (rate-limiting.md)",
                ),
            )
        }
    }

    return result
}

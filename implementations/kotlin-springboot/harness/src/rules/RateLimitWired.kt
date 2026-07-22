package harness.rules

import harness.*
import java.io.File

private val BLOCK_COMMENT = Regex("""/\*[\s\S]*?\*/""")
private val LINE_COMMENT = Regex("""//[^\n]*""")

private fun stripComments(content: String): String =
    content.replace(BLOCK_COMMENT, "").replace(LINE_COMMENT, "")

// Doesn't require a trailing word boundary(\b) in order to catch RateLimiterRegistry/@RateLimiter/
// RateLimiter types alike — identifiers where another word character immediately follows
// "RateLimiter", like "RateLimiterRegistry", are the dominant form in the actual code, so
// \bRateLimiter\b(boundary on both sides) would catch nothing(a bug actually hit in practice).
private val RATE_LIMITER_REFERENCE = Regex("""\bRateLimiter""")
private val CLASS_NAME = Regex("""class\s+(\w+)""")
private val ACQUIRE_CALL = Regex("""\.acquirePermission\(|\.tryAcquirePermission\(""")
private val SPRING_BEAN_ANNOTATION = Regex("""@(Component|Service|Configuration|Bean)\b""")
private val EXPLICIT_FILTER_REGISTRATION = Regex("""FilterRegistrationBean|addFilterBefore|addFilterAfter|addFilter\b""")

/**
 * [S5] rate-limit-wired — checks whether an `OncePerRequestFilter`(or Servlet `Filter`) class that
 * references the Resilience4j `RateLimiter` is actually registered with the Spring container/filter
 * chain(rate-limiting.md). If it has no bean-registration annotation like `@Component`, and no
 * explicit `FilterRegistrationBean`/`addFilterBefore` registration in something like
 * `WebConfig.kt`/`SecurityConfig.kt`, the class compiles but is dead code that no request ever
 * actually passes through. This also catches the case where RateLimiter is only referenced with no
 * actual limiting-logic call like `acquirePermission()`(a stub that's only defined and doesn't
 * substantively filter anything).
 */
fun checkRateLimitWired(rootPath: String): RuleResult {
    val root = File(rootPath)
    val result = RuleResult("rate-limit-wired")
    val allFiles = collectKtFiles(root)
    val contents = allFiles.associateWith { stripComments(it.readText()) }

    val usesRateLimiter = contents.values.any { RATE_LIMITER_REFERENCE.containsMatchIn(it) }
    if (!usesRateLimiter) {
        result.add(skipFinding("no use of RateLimiter(Resilience4j)"))
        return result
    }

    val filterFiles =
        allFiles.filter { f ->
            val content = contents.getValue(f)
            content.contains("OncePerRequestFilter") && RATE_LIMITER_REFERENCE.containsMatchIn(content)
        }

    if (filterFiles.isEmpty()) {
        result.add(skipFinding("no Filter class uses RateLimiter (may be applied a different way, e.g. annotation-based)"))
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
                    "$className references RateLimiter but has no actual limiting-logic call like acquirePermission() — " +
                        "suspected to be a stub that's only defined and doesn't substantively limit requests (rate-limiting.md)",
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
            result.add(passFinding("$rel ($className, registered in the request pipeline)"))
        } else {
            result.add(
                failFinding(
                    rel,
                    "$className has logic that limits requests via RateLimiter, but has neither a bean-registration " +
                        "annotation like @Component nor an explicit FilterRegistrationBean/addFilterBefore registration — " +
                        "suspected to be dead code not actually applied to the filter chain (rate-limiting.md)",
                ),
            )
        }
    }

    return result
}

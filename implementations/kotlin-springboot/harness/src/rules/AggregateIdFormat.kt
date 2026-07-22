package harness.rules

import harness.*
import java.io.File

private val USES_RANDOM_UUID = Regex("""UUID\.randomUUID\(\)""")

// Whether generateId() removes hyphens — broadly catches the .replace("-", "") / .replace('-', ' ')
// family or Regex("-")-based removal. To avoid being tied to the exact string-literal quote style,
// only the opening quote character is captured and matching is loose about whether it's closed by the
// same character(guarding more against false negatives than false positives).
private val STRIPS_HYPHEN = Regex("""\.replace\(\s*["']-["']|\.replace\(\s*Regex\(\s*["']-["']|\.replace\(\s*'-'""")

/**
 * [S1] aggregate-id-format — checks that the Aggregate ID generation utility(`generateId()`) does not
 * return a hyphenated UUID that's just `UUID.randomUUID()` stringified as-is, and instead returns a
 * 32-digit hex string with hyphens removed(aggregate-id.md). A single-file check across the whole
 * project that looks for the specific file name `GenerateId.kt` — this repository's ID generation
 * utility is not per-domain but a single shared utility living in `common/`(the same rationale as the
 * OutboxWriter/OutboxPoller literal-name check in SharedInfra.kt), so a file-name-based check is
 * appropriate here rather than a path-based structural check.
 */
fun checkAggregateIdFormat(rootPath: String): RuleResult {
    val root = File(rootPath)
    val result = RuleResult("aggregate-id-format")

    val candidates = collectKtFiles(root).filter { it.name == "GenerateId.kt" }
    if (candidates.isEmpty()) {
        result.add(skipFinding("no GenerateId.kt"))
        return result
    }

    for (f in candidates) {
        val rel = f.relTo(root)
        val content = f.readText()

        if (!USES_RANDOM_UUID.containsMatchIn(content)) {
            // If UUID.randomUUID() isn't used at all, the "raw UUID stringification" risk this rule
            // assumes doesn't exist in the first place — it may be a different generation strategy
            // (e.g. a separate library), so this doesn't fail.
            result.add(skipFinding("$rel — UUID.randomUUID() not used, judged to be a different ID generation strategy and not targeted"))
            continue
        }

        if (STRIPS_HYPHEN.containsMatchIn(content)) {
            result.add(passFinding("$rel (confirmed hyphen removal)"))
        } else {
            result.add(
                failFinding(
                    rel,
                    "appears to return UUID.randomUUID().toString() as-is without removing hyphens — " +
                        "a hyphenated UUID, not a 32-digit hex string, ends up used as the Aggregate ID (aggregate-id.md)",
                ),
            )
        }
    }

    return result
}

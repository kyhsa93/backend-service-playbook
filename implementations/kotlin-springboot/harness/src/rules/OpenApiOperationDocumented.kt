package harness.rules

import harness.*
import java.io.File

private val BLOCK_COMMENT = Regex("""/\*[\s\S]*?\*/""")
private val LINE_COMMENT = Regex("""//[^\n]*""")

private fun stripComments(content: String): String =
    content.replace(BLOCK_COMMENT, "").replace(LINE_COMMENT, "")

// Only the method-level HTTP mapping annotations — @RequestMapping is excluded because in this
// codebase it is used at the class level for the base path, not to mark an individual operation.
private val MAPPING_ANNOTATION = Regex("""@(?:Get|Post|Put|Patch|Delete)Mapping\b""")
private val FUN_DECL = Regex("""\bfun\s+(\w+)\s*\(""")
private val OPERATION_START = Regex("""@Operation\s*\(""")
private val SUMMARY_FIELD = Regex("""\bsummary\s*=\s*"([^"]+)"""")
private val DESCRIPTION_FIELD = Regex("""\bdescription\s*=\s*"([^"]+)"""")
private val NON_2XX_RESPONSE_CODE = Regex("""responseCode\s*=\s*"[3-5]\d\d"""")

private const val DOC_REF = "docs/architecture/api-response.md — every operation needs a summary+description, and every non-2xx status it can return must be documented"

/**
 * Accounting for nested parentheses, returns the inner text up to the ')' matching the '(' at
 * openParenIndex. Same helper pattern as ErrorResponseSchema.kt/RepositoryNaming.kt.
 */
private fun extractBalancedParens(code: String, openParenIndex: Int): String? {
    if (code.getOrNull(openParenIndex) != '(') return null
    var depth = 0
    for (i in openParenIndex until code.length) {
        when (code[i]) {
            '(' -> depth++
            ')' -> {
                depth--
                if (depth == 0) return code.substring(openParenIndex + 1, i)
            }
        }
    }
    return null
}

/**
 * [S3] openapi-operation-documented — for every `@RestController` method mapped with
 * `@GetMapping`/`@PostMapping`/`@PutMapping`/`@PatchMapping`/`@DeleteMapping`, checks that:
 * 1. an `@Operation` annotation is attached with both a non-empty `summary` and `description` — an
 *    operation with only a bare route (or only an `operationId`) is not sufficient.
 * 2. at least one non-2xx `@ApiResponse`/`@ApiResponses` `responseCode` is documented for that
 *    operation — either on the method itself, or on the class (e.g. a `@RestController`-class-level
 *    `@ApiResponses(@ApiResponse(responseCode = "401", ...))` shared by every method in that
 *    Controller, such as `AccountController`'s bearer-token 401).
 *
 * Only documenting the success response is the most common way this rots — it looks complete because
 * the page renders, but a client has no way to know what a 404/409/etc. looks like (api-response.md).
 * This is a structural/mechanical check (annotation presence + non-empty text), not a check of whether
 * the documented codes are the *correct* ones for the domain's business rules — that judgment is left to
 * a human/agent review, consistent with harness.md's design principle of not assuming business-domain
 * knowledge.
 */
fun checkOpenApiOperationDocumented(rootPath: String): RuleResult {
    val root = File(rootPath)
    val result = RuleResult("openapi-operation-documented")
    var found = false

    for (f in collectKtFiles(root)) {
        if (!f.pathContains("/interfaces/")) continue
        val code = stripComments(f.readText())
        if (!MAPPING_ANNOTATION.containsMatchIn(code)) continue
        val rel = f.relTo(root)

        // The class-level annotation block: everything before the first mapping annotation in the
        // file — includes @RestController/@Tag/@SecurityRequirement/@ApiResponses placed above the
        // class declaration (and, incidentally, the constructor — but that never contains a false
        // positive match for responseCode).
        val firstMappingIdx = MAPPING_ANNOTATION.find(code)!!.range.first
        val classLevelBlock = code.substring(0, firstMappingIdx)
        val classLevelHasNon2xx = NON_2XX_RESPONSE_CODE.containsMatchIn(classLevelBlock)

        for (mappingMatch in MAPPING_ANNOTATION.findAll(code)) {
            found = true
            val blockStart = mappingMatch.range.first
            // The method's own annotation block runs from its mapping annotation up to the `fun`
            // keyword that follows it (the next mapping annotation, if any, always comes after the
            // next `fun` in this codebase's one-mapping-per-method style, so this never overreaches
            // into the next method's annotations).
            val funMatch = FUN_DECL.find(code, blockStart) ?: continue
            val methodName = funMatch.groupValues[1]
            val block = code.substring(blockStart, funMatch.range.first)

            val operationMatch = OPERATION_START.find(block)
            if (operationMatch == null) {
                result.add(failFinding("$rel ($methodName)", "no @Operation annotation — $DOC_REF"))
                continue
            }
            val operationBody = extractBalancedParens(block, operationMatch.range.last) ?: ""
            val hasSummary = SUMMARY_FIELD.find(operationBody)?.groupValues?.get(1)?.isNotBlank() == true
            val hasDescription = DESCRIPTION_FIELD.find(operationBody)?.groupValues?.get(1)?.isNotBlank() == true

            val missingParts = mutableListOf<String>()
            if (!hasSummary) missingParts += "summary"
            if (!hasDescription) missingParts += "description"

            val hasNon2xx = classLevelHasNon2xx || NON_2XX_RESPONSE_CODE.containsMatchIn(block)
            if (!hasNon2xx) missingParts += "a non-2xx @ApiResponse"

            if (missingParts.isNotEmpty()) {
                result.add(failFinding("$rel ($methodName)", "missing ${missingParts.joinToString(", ")} — $DOC_REF"))
            } else {
                result.add(passFinding("$rel ($methodName)"))
            }
        }
    }

    if (!found) result.add(skipFinding("no @GetMapping/@PostMapping/@PutMapping/@PatchMapping/@DeleteMapping in interfaces/"))
    return result
}

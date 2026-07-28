// Kotlin Spring Boot Harness — checks Kotlin project structure/annotation rules
// Usage: java -jar build/harness.jar <projectRoot>  (or use the harness.sh wrapper)
//
// Each rule is implemented in its own file under rules/, and each returns a RuleResult —
// this file only serves as the CLI entry point that defines the rule list and aggregates/prints results.
// Per-rule regression tests are verified with test/RuleTest.kt + test/testdata/<rule>/ fixtures
// (see README.md).
package harness

import harness.rules.checkAggregateIdFormat
import harness.rules.checkAggregateNoPublicSetters
import harness.rules.checkControllerPlacement
import harness.rules.checkCqrsPattern
import harness.rules.checkDockerfileConventions
import harness.rules.checkDomainLayerIsolation
import harness.rules.checkDomainPurity
import harness.rules.checkErrorResponseSchema
import harness.rules.checkEventPlacement
import harness.rules.checkFileNaming
import harness.rules.checkInterfaceNoInfrastructure
import harness.rules.checkNoCrossAggregateReference
import harness.rules.checkNoCrossBcDomainImport
import harness.rules.checkNoCrossBcRepositoryInApplication
import harness.rules.checkNoDirectEnvAccessOutsideConfig
import harness.rules.checkNoEventPublisherInCommand
import harness.rules.checkNoGenericResponseKeys
import harness.rules.checkNoLoggingInDomain
import harness.rules.checkNoOrmAutosyncInProdConfig
import harness.rules.checkNoSilentCatch
import harness.rules.checkNotificationE2eTest
import harness.rules.checkOpenApiOperationDocumented
import harness.rules.checkOutboxNoSyncDrain
import harness.rules.checkPackageStructure
import harness.rules.checkQueryHandlerNoRawAggregate
import harness.rules.checkRateLimitWired
import harness.rules.checkRepositoryAnnotation
import harness.rules.checkRepositoryNaming
import harness.rules.checkSchedulerInInfrastructureOnly
import harness.rules.checkSealedException
import harness.rules.checkServiceAnnotation
import harness.rules.checkSharedInfra
import harness.rules.checkSoftDeleteFilter
import harness.rules.checkTransactionBoundary
import harness.rules.checkTypedErrorsOnly
import kotlin.math.roundToInt
import kotlin.system.exitProcess

// ruleMaxScore is the fixed per-rule point budget. A rule contributes
// ruleMaxScore * (passCount / (passCount+failCount)) to the raw score when it produced at
// least one non-SKIP finding, and 0 (excluded from the normalization denominator) when
// every finding was SKIP — mirroring the nestjs harness's "maxScore = 0 means not
// applicable" convention.
private const val RULE_MAX_SCORE = 20

private enum class Category { STRUCTURE, ARCHITECTURE, RUNTIME, TESTING, API, SEMANTICS }

// Assigns a rule's section name to one of the nestjs harness's 6 score-breakdown
// categories, reusing the same substring-matching approach as
// evaluators/shared/score.ts so results stay comparable across languages. Returns null for
// rules with no clear category (also mirrors nestjs, where not every evaluator lands in a
// bucket — e.g. file-naming).
private fun bucketFor(name: String): Category? = when {
    name.contains("structure") -> Category.STRUCTURE
    name.contains("layer") ||
        name.contains("repository") ||
        name.contains("cqrs") ||
        name.contains("scheduler") ||
        name.contains("domain-purity") ||
        name.contains("interface-no-infrastructure") ||
        name.contains("no-cross-aggregate-reference") ||
        name.contains("no-cross-bc-domain-import") ||
        name.contains("soft-delete-filter") ||
        name.contains("query-handler-no-raw-aggregate") ||
        name.contains("outbox") ||
        name.contains("shared-infra") ||
        name.contains("no-direct-env-access") ||
        name.contains("aggregate-id") ||
        name.contains("aggregate-no-public-setters") ||
        name.contains("logging") ||
        name.contains("error-response-schema") ||
        name.contains("typed-errors-only") ||
        name.contains("no-silent-catch") ||
        name.contains("no-event-publisher-in-command") ||
        name.contains("service-annotation") ||
        name.contains("sealed-exception") ||
        name.contains("transaction-boundary") -> Category.ARCHITECTURE
    name.contains("dockerfile") || name.contains("no-orm-autosync-in-prod-config") -> Category.RUNTIME
    name.contains("notification-e2e-test") -> Category.TESTING
    name.contains("no-generic-response-keys") ||
        name.contains("openapi-operation-documented") ||
        name.contains("rate-limit-wired") ||
        name.contains("controller-placement") -> Category.API
    else -> null
}

private fun gradeFor(total: Int): String = when {
    total >= 90 -> "A"
    total >= 80 -> "B"
    total >= 70 -> "C"
    total >= 60 -> "D"
    else -> "F"
}

val RULES: List<Rule> = listOf(
    ::checkFileNaming,
    ::checkRepositoryAnnotation,
    ::checkServiceAnnotation,
    ::checkDomainPurity,
    ::checkControllerPlacement,
    ::checkSealedException,
    ::checkPackageStructure,
    ::checkSharedInfra,
    ::checkEventPlacement,
    ::checkNoEventPublisherInCommand,
    ::checkTransactionBoundary,
    ::checkOutboxNoSyncDrain,
    ::checkCqrsPattern,
    ::checkNotificationE2eTest,
    ::checkRepositoryNaming,
    ::checkDomainLayerIsolation,
    ::checkInterfaceNoInfrastructure,
    ::checkAggregateNoPublicSetters,
    ::checkNoCrossAggregateReference,
    ::checkNoDirectEnvAccessOutsideConfig,
    ::checkNoCrossBcRepositoryInApplication,
    ::checkNoLoggingInDomain,
    ::checkSchedulerInInfrastructureOnly,
    ::checkNoSilentCatch,
    ::checkDockerfileConventions,
    ::checkAggregateIdFormat,
    ::checkErrorResponseSchema,
    ::checkSoftDeleteFilter,
    ::checkTypedErrorsOnly,
    ::checkRateLimitWired,
    ::checkNoGenericResponseKeys,
    ::checkQueryHandlerNoRawAggregate,
    ::checkNoCrossBcDomainImport,
    ::checkNoOrmAutosyncInProdConfig,
    ::checkOpenApiOperationDocumented
)

fun main(args: Array<String>) {
    val root = if (args.isNotEmpty()) args[0] else "."

    var passCount = 0
    var failCount = 0
    var rawScore = 0.0
    var rawMax = 0
    var skippedRules = 0
    val breakdown = mutableMapOf<Category, Double>()
    val breakdownMax = mutableMapOf<Category, Int>()

    for (rule in RULES) {
        val result = rule(root)
        println("\n[${result.section}]")

        var rulePass = 0
        var ruleFail = 0
        for (finding in result.findings) {
            when (finding.kind) {
                Kind.PASS -> {
                    passCount++
                    rulePass++
                    println("  PASS  ${finding.name}")
                }
                Kind.FAIL -> {
                    failCount++
                    ruleFail++
                    println("  FAIL  ${finding.name} — ${finding.reason}")
                }
                Kind.SKIP -> println("  SKIP  ${finding.name}")
            }
        }

        if (rulePass + ruleFail == 0) {
            skippedRules++
            continue
        }

        val ruleScore = RULE_MAX_SCORE * rulePass.toDouble() / (rulePass + ruleFail)
        rawScore += ruleScore
        rawMax += RULE_MAX_SCORE

        bucketFor(result.section)?.let { category ->
            breakdown.merge(category, ruleScore, Double::plus)
            breakdownMax.merge(category, RULE_MAX_SCORE, Int::plus)
        }
    }

    val total = if (rawMax > 0) (rawScore / rawMax * 100).roundToInt() else 0

    println("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
    println(
        "${gradeFor(total)} ($total/100, raw ${"%.1f".format(rawScore)}/$rawMax) — " +
            "$failCount failure(s) across ${RULES.size} evaluator(s), $skippedRules skipped (not applicable)"
    )
    for (category in Category.values()) {
        val max = breakdownMax[category] ?: 0
        if (max > 0) {
            println("  ${category.name.lowercase().padEnd(13)} ${"%.1f".format(breakdown[category])}/$max")
        }
    }

    if (failCount == 0) {
        println("$passCount passed  PASS")
    } else {
        println("$passCount passed, $failCount failed  FAIL")
        exitProcess(1)
    }
}

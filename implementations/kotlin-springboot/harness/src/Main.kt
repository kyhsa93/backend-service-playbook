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
import kotlin.system.exitProcess

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
    ::checkNoOrmAutosyncInProdConfig
)

fun main(args: Array<String>) {
    val root = if (args.isNotEmpty()) args[0] else "."

    var passCount = 0
    var failCount = 0
    for (rule in RULES) {
        val result = rule(root)
        println("\n[${result.section}]")
        for (finding in result.findings) {
            when (finding.kind) {
                Kind.PASS -> {
                    passCount++
                    println("  PASS  ${finding.name}")
                }
                Kind.FAIL -> {
                    failCount++
                    println("  FAIL  ${finding.name} — ${finding.reason}")
                }
                Kind.SKIP -> println("  SKIP  ${finding.name}")
            }
        }
    }

    println("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
    if (failCount == 0) {
        println("$passCount passed  PASS")
    } else {
        println("$passCount passed, $failCount failed  FAIL")
        exitProcess(1)
    }
}

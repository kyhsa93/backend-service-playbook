// Kotlin Spring Boot Harness — Kotlin 프로젝트 구조·어노테이션 규칙 검사
// Usage: java -jar build/harness.jar <projectRoot>  (또는 harness.sh 래퍼 사용)
//
// 각 규칙은 rules/ 아래 별도 파일에 구현되어 있고, 각각 RuleResult를 반환한다 —
// 이 파일은 규칙 목록을 정의하고 결과를 집계·출력하는 CLI 진입점 역할만 한다.
// 규칙별 회귀 테스트는 test/RuleTest.kt + test/testdata/<rule>/ fixture로 검증한다
// (README.md 참고).
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
import harness.rules.checkNoCrossBcRepositoryInApplication
import harness.rules.checkNoDirectEnvAccessOutsideConfig
import harness.rules.checkNoEventPublisherInCommand
import harness.rules.checkNoLoggingInDomain
import harness.rules.checkNoSilentCatch
import harness.rules.checkNotificationE2eTest
import harness.rules.checkOutboxNoSyncDrain
import harness.rules.checkPackageStructure
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
    ::checkRateLimitWired
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

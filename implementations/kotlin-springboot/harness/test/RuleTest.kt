// 규칙별 회귀 테스트 — testdata/<rule>/good|bad-*/ 를 대상으로 실행한다.
//
// JUnit 등 외부 테스트 프레임워크를 새로 끌어오지 않고, 실패 시 AssertionError를 던지는
// 자체 assert 함수 하나만으로 작성된 가벼운 러너다(nestjs harness의 run-fixtures.ts와
// 같은 취지 — kotlin.test 아티팩트조차 별도 의존성이라 끌어오지 않는다). `test/`
// 디렉토리에서 실행한다고 가정하고 "testdata/..." 상대경로를 그대로 쓴다.
package harness.test

import harness.Kind
import harness.RuleResult
import harness.rules.checkAggregateNoPublicSetters
import harness.rules.checkControllerPlacement
import harness.rules.checkCqrsPattern
import harness.rules.checkDockerfileConventions
import harness.rules.checkDomainLayerIsolation
import harness.rules.checkDomainPurity
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
import harness.rules.checkRepositoryAnnotation
import harness.rules.checkRepositoryNaming
import harness.rules.checkSchedulerInInfrastructureOnly
import harness.rules.checkSealedException
import harness.rules.checkServiceAnnotation
import harness.rules.checkSharedInfra
import harness.rules.checkTransactionBoundary
import kotlin.system.exitProcess

private fun assertTrue(condition: Boolean, message: String) {
    if (!condition) throw AssertionError(message)
}

private fun RuleResult.assertNoFailures() {
    val failures = findings.filter { it.kind == Kind.FAIL }
    assertTrue(failures.isEmpty(), "expected no failures, got: ${failures.map { "${it.name} — ${it.reason}" }}")
}

private fun RuleResult.assertHasFailure() {
    assertTrue(findings.any { it.kind == Kind.FAIL }, "expected at least one failure, got none")
}

data class TestCase(val name: String, val run: () -> Unit)

val TESTS: List<TestCase> = listOf(
    TestCase("file-naming/good") { checkFileNaming("testdata/file-naming/good").assertNoFailures() },
    TestCase("file-naming/bad-camelcase") { checkFileNaming("testdata/file-naming/bad-camelcase").assertHasFailure() },

    TestCase("repository-annotation/good") { checkRepositoryAnnotation("testdata/repository-annotation/good").assertNoFailures() },
    TestCase("repository-annotation/bad-outside-infrastructure") { checkRepositoryAnnotation("testdata/repository-annotation/bad-outside-infrastructure").assertHasFailure() },

    TestCase("service-annotation/good") { checkServiceAnnotation("testdata/service-annotation/good").assertNoFailures() },
    TestCase("service-annotation/bad-outside-application") { checkServiceAnnotation("testdata/service-annotation/bad-outside-application").assertHasFailure() },

    TestCase("domain-purity/good") { checkDomainPurity("testdata/domain-purity/good").assertNoFailures() },
    TestCase("domain-purity/bad-forbidden-annotation") { checkDomainPurity("testdata/domain-purity/bad-forbidden-annotation").assertHasFailure() },
    TestCase("domain-purity/bad-jpa-annotation") { checkDomainPurity("testdata/domain-purity/bad-jpa-annotation").assertHasFailure() },

    TestCase("controller-placement/good") { checkControllerPlacement("testdata/controller-placement/good").assertNoFailures() },
    TestCase("controller-placement/bad-outside-interfaces") { checkControllerPlacement("testdata/controller-placement/bad-outside-interfaces").assertHasFailure() },
    TestCase("controller-placement/good-restcontrolleradvice-outside-interfaces") { checkControllerPlacement("testdata/controller-placement/good-restcontrolleradvice-outside-interfaces").assertNoFailures() },

    TestCase("sealed-exception/good") { checkSealedException("testdata/sealed-exception/good").assertNoFailures() },
    TestCase("sealed-exception/bad-outside-domain") { checkSealedException("testdata/sealed-exception/bad-outside-domain").assertHasFailure() },
    TestCase("sealed-exception/good-comment-mentions-sealed-class") { checkSealedException("testdata/sealed-exception/good-comment-mentions-sealed-class").assertNoFailures() },

    TestCase("package-structure/good") { checkPackageStructure("testdata/package-structure/good").assertNoFailures() },
    TestCase("package-structure/bad-missing-layer") { checkPackageStructure("testdata/package-structure/bad-missing-layer").assertHasFailure() },

    TestCase("shared-infra/good") { checkSharedInfra("testdata/shared-infra/good").assertNoFailures() },
    TestCase("shared-infra/bad-outbox-missing-files") { checkSharedInfra("testdata/shared-infra/bad-outbox-missing-files").assertHasFailure() },
    TestCase("shared-infra/bad-task-queue-misplaced") { checkSharedInfra("testdata/shared-infra/bad-task-queue-misplaced").assertHasFailure() },

    TestCase("event-placement/good") { checkEventPlacement("testdata/event-placement/good").assertNoFailures() },
    TestCase("event-placement/bad-wrong-dir") { checkEventPlacement("testdata/event-placement/bad-wrong-dir").assertHasFailure() },

    TestCase("no-event-publisher-in-command/good") { checkNoEventPublisherInCommand("testdata/no-event-publisher-in-command/good").assertNoFailures() },
    TestCase("no-event-publisher-in-command/bad-has-publisher") { checkNoEventPublisherInCommand("testdata/no-event-publisher-in-command/bad-has-publisher").assertHasFailure() },

    TestCase("transaction-boundary/good") { checkTransactionBoundary("testdata/transaction-boundary/good").assertNoFailures() },
    TestCase("transaction-boundary/bad-has-transactional") { checkTransactionBoundary("testdata/transaction-boundary/bad-has-transactional").assertHasFailure() },
    TestCase("transaction-boundary/bad-repository-impl-missing-transactional") { checkTransactionBoundary("testdata/transaction-boundary/bad-repository-impl-missing-transactional").assertHasFailure() },

    TestCase("outbox-no-sync-drain/good") { checkOutboxNoSyncDrain("testdata/outbox-no-sync-drain/good").assertNoFailures() },
    TestCase("outbox-no-sync-drain/bad-references-relay") { checkOutboxNoSyncDrain("testdata/outbox-no-sync-drain/bad-references-relay").assertHasFailure() },
    TestCase("outbox-no-sync-drain/bad-calls-process-pending") { checkOutboxNoSyncDrain("testdata/outbox-no-sync-drain/bad-calls-process-pending").assertHasFailure() },

    TestCase("notification-e2e-test/good") { checkNotificationE2eTest("testdata/notification-e2e-test/good").assertNoFailures() },
    TestCase("notification-e2e-test/bad-missing") { checkNotificationE2eTest("testdata/notification-e2e-test/bad-missing").assertHasFailure() },

    TestCase("cqrs-pattern/good") { checkCqrsPattern("testdata/cqrs-pattern/good").assertNoFailures() },
    TestCase("cqrs-pattern/bad-query-uses-repository") { checkCqrsPattern("testdata/cqrs-pattern/bad-query-uses-repository").assertHasFailure() },

    TestCase("repository-naming/good") { checkRepositoryNaming("testdata/repository-naming/good").assertNoFailures() },
    TestCase("repository-naming/good-infra-excluded") { checkRepositoryNaming("testdata/repository-naming/good-infra-excluded").assertNoFailures() },
    TestCase("repository-naming/bad-findby") { checkRepositoryNaming("testdata/repository-naming/bad-findby").assertHasFailure() },
    TestCase("repository-naming/bad-findall") { checkRepositoryNaming("testdata/repository-naming/bad-findall").assertHasFailure() },
    TestCase("repository-naming/bad-count") { checkRepositoryNaming("testdata/repository-naming/bad-count").assertHasFailure() },
    TestCase("repository-naming/bad-bare-save") { checkRepositoryNaming("testdata/repository-naming/bad-bare-save").assertHasFailure() },
    TestCase("repository-naming/bad-bare-delete") { checkRepositoryNaming("testdata/repository-naming/bad-bare-delete").assertHasFailure() },
    TestCase("repository-naming/bad-update") { checkRepositoryNaming("testdata/repository-naming/bad-update").assertHasFailure() },

    TestCase("domain-layer-isolation/good") { checkDomainLayerIsolation("testdata/domain-layer-isolation/good").assertNoFailures() },
    TestCase("domain-layer-isolation/bad-same-domain-application") { checkDomainLayerIsolation("testdata/domain-layer-isolation/bad-same-domain-application").assertHasFailure() },
    TestCase("domain-layer-isolation/bad-sibling-domain-infrastructure") { checkDomainLayerIsolation("testdata/domain-layer-isolation/bad-sibling-domain-infrastructure").assertHasFailure() },

    TestCase("interface-no-infrastructure/good") { checkInterfaceNoInfrastructure("testdata/interface-no-infrastructure/good").assertNoFailures() },
    TestCase("interface-no-infrastructure/bad-imports-infrastructure") { checkInterfaceNoInfrastructure("testdata/interface-no-infrastructure/bad-imports-infrastructure").assertHasFailure() },

    TestCase("aggregate-no-public-setters/good") { checkAggregateNoPublicSetters("testdata/aggregate-no-public-setters/good").assertNoFailures() },
    TestCase("aggregate-no-public-setters/bad-public-var") { checkAggregateNoPublicSetters("testdata/aggregate-no-public-setters/bad-public-var").assertHasFailure() },

    TestCase("no-cross-aggregate-reference/good") { checkNoCrossAggregateReference("testdata/no-cross-aggregate-reference/good").assertNoFailures() },
    TestCase("no-cross-aggregate-reference/bad-payment-holds-refund") { checkNoCrossAggregateReference("testdata/no-cross-aggregate-reference/bad-payment-holds-refund").assertHasFailure() },
    TestCase("no-cross-aggregate-reference/bad-refund-holds-payment") { checkNoCrossAggregateReference("testdata/no-cross-aggregate-reference/bad-refund-holds-payment").assertHasFailure() },

    TestCase("no-direct-env-access-outside-config/good") { checkNoDirectEnvAccessOutsideConfig("testdata/no-direct-env-access-outside-config/good").assertNoFailures() },
    TestCase("no-direct-env-access-outside-config/bad-getenv-in-application") { checkNoDirectEnvAccessOutsideConfig("testdata/no-direct-env-access-outside-config/bad-getenv-in-application").assertHasFailure() },

    TestCase("no-cross-bc-repository-in-application/good") { checkNoCrossBcRepositoryInApplication("testdata/no-cross-bc-repository-in-application/good").assertNoFailures() },
    TestCase("no-cross-bc-repository-in-application/bad-cross-domain-repository") { checkNoCrossBcRepositoryInApplication("testdata/no-cross-bc-repository-in-application/bad-cross-domain-repository").assertHasFailure() },

    TestCase("no-logging-in-domain/good") { checkNoLoggingInDomain("testdata/no-logging-in-domain/good").assertNoFailures() },
    TestCase("no-logging-in-domain/bad-slf4j-in-domain") { checkNoLoggingInDomain("testdata/no-logging-in-domain/bad-slf4j-in-domain").assertHasFailure() },

    TestCase("scheduler-in-infrastructure-only/good") { checkSchedulerInInfrastructureOnly("testdata/scheduler-in-infrastructure-only/good").assertNoFailures() },
    TestCase("scheduler-in-infrastructure-only/bad-scheduled-in-application") { checkSchedulerInInfrastructureOnly("testdata/scheduler-in-infrastructure-only/bad-scheduled-in-application").assertHasFailure() },

    TestCase("no-silent-catch/good") { checkNoSilentCatch("testdata/no-silent-catch/good").assertNoFailures() },
    TestCase("no-silent-catch/bad-empty-catch") { checkNoSilentCatch("testdata/no-silent-catch/bad-empty-catch").assertHasFailure() },

    TestCase("dockerfile-conventions/good") { checkDockerfileConventions("testdata/dockerfile-conventions/good").assertNoFailures() },
    TestCase("dockerfile-conventions/bad-single-stage-no-healthcheck") { checkDockerfileConventions("testdata/dockerfile-conventions/bad-single-stage-no-healthcheck").assertHasFailure() },
    TestCase("dockerfile-conventions/bad-missing-dockerignore") { checkDockerfileConventions("testdata/dockerfile-conventions/bad-missing-dockerignore").assertHasFailure() }
)

fun main() {
    var failures = 0
    for (t in TESTS) {
        try {
            t.run()
            println("  PASS  ${t.name}")
        } catch (e: AssertionError) {
            failures++
            println("  FAIL  ${t.name} — ${e.message}")
        }
    }
    println()
    if (failures == 0) {
        println("${TESTS.size} passed  PASS")
    } else {
        println("${TESTS.size - failures} passed, $failures failed  FAIL")
        exitProcess(1)
    }
}

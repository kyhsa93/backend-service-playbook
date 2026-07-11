// 규칙별 회귀 테스트 — testdata/<rule>/good|bad-*/ 를 대상으로 실행한다.
//
// JUnit 등 외부 테스트 프레임워크를 새로 끌어오지 않고, 실패 시 AssertionError를 던지는
// 자체 assert 함수 하나만으로 작성된 가벼운 러너다(nestjs harness의 run-fixtures.ts와
// 같은 취지 — kotlin.test 아티팩트조차 별도 의존성이라 끌어오지 않는다). `test/`
// 디렉토리에서 실행한다고 가정하고 "testdata/..." 상대경로를 그대로 쓴다.
package harness.test

import harness.Kind
import harness.RuleResult
import harness.rules.checkControllerPlacement
import harness.rules.checkDomainPurity
import harness.rules.checkEventPlacement
import harness.rules.checkFileNaming
import harness.rules.checkNoEventPublisherInCommand
import harness.rules.checkNotificationE2eTest
import harness.rules.checkOutboxDrainOrder
import harness.rules.checkPackageStructure
import harness.rules.checkRepositoryAnnotation
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

    TestCase("sealed-exception/good") { checkSealedException("testdata/sealed-exception/good").assertNoFailures() },
    TestCase("sealed-exception/bad-outside-domain") { checkSealedException("testdata/sealed-exception/bad-outside-domain").assertHasFailure() },

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

    TestCase("outbox-drain-order/good") { checkOutboxDrainOrder("testdata/outbox-drain-order/good").assertNoFailures() },
    TestCase("outbox-drain-order/bad-missing-process-pending") { checkOutboxDrainOrder("testdata/outbox-drain-order/bad-missing-process-pending").assertHasFailure() },
    TestCase("outbox-drain-order/bad-wrong-order") { checkOutboxDrainOrder("testdata/outbox-drain-order/bad-wrong-order").assertHasFailure() },

    TestCase("notification-e2e-test/good") { checkNotificationE2eTest("testdata/notification-e2e-test/good").assertNoFailures() },
    TestCase("notification-e2e-test/bad-missing") { checkNotificationE2eTest("testdata/notification-e2e-test/bad-missing").assertHasFailure() }
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

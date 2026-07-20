// 규칙별 회귀 테스트 — testdata/<rule>/good|bad-*/ 를 대상으로 실행한다.
//
// JUnit 등 외부 테스트 프레임워크를 새로 끌어오지 않고, 실패 시 AssertionError를 던지는
// 자체 assert 메서드 하나만으로 작성된 가벼운 러너다(nestjs harness의 run-fixtures.ts와
// 같은 취지). test/ 디렉토리에서 실행한다고 가정하고 "testdata/..." 상대경로를 그대로 쓴다.
import harness.Kind;
import harness.RuleResult;
import harness.rules.ControllerPlacement;
import harness.rules.CqrsQueryPurity;
import harness.rules.DomainPurity;
import harness.rules.EventPlacement;
import harness.rules.FileNaming;
import harness.rules.NoEventPublisherInCommand;
import harness.rules.OutboxDrainOrder;
import harness.rules.PackageStructure;
import harness.rules.RepositoryAnnotation;
import harness.rules.RepositoryNaming;
import harness.rules.ServiceAnnotation;
import harness.rules.SharedInfra;
import harness.rules.TransactionBoundary;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public final class RuleTest {
    private RuleTest() {
    }

    private static void assertNoFailures(RuleResult result) {
        long failures = result.count(Kind.FAIL);
        if (failures > 0) {
            throw new AssertionError("expected no failures, got: " + describeFailures(result));
        }
    }

    private static void assertHasFailure(RuleResult result) {
        if (result.count(Kind.FAIL) == 0) {
            throw new AssertionError("expected at least one failure, got none");
        }
    }

    private static String describeFailures(RuleResult result) {
        StringBuilder sb = new StringBuilder();
        for (var f : result.findings) {
            if (f.kind == Kind.FAIL) sb.append(f.name).append(" — ").append(f.reason).append("; ");
        }
        return sb.toString();
    }

    private record TestCase(String name, Runnable run) {
    }

    private static final List<TestCase> TESTS = List.of(
        new TestCase("file-naming/good", () -> assertNoFailures(FileNaming.check("testdata/file-naming/good"))),
        new TestCase("file-naming/bad-camelcase", () -> assertHasFailure(FileNaming.check("testdata/file-naming/bad-camelcase"))),

        new TestCase("repository-annotation/good", () -> assertNoFailures(RepositoryAnnotation.check("testdata/repository-annotation/good"))),
        new TestCase("repository-annotation/bad-outside-infrastructure", () -> assertHasFailure(RepositoryAnnotation.check("testdata/repository-annotation/bad-outside-infrastructure"))),

        new TestCase("service-annotation/good", () -> assertNoFailures(ServiceAnnotation.check("testdata/service-annotation/good"))),
        new TestCase("service-annotation/bad-outside-application", () -> assertHasFailure(ServiceAnnotation.check("testdata/service-annotation/bad-outside-application"))),

        new TestCase("domain-purity/good", () -> assertNoFailures(DomainPurity.check("testdata/domain-purity/good"))),
        new TestCase("domain-purity/bad-forbidden-annotation", () -> assertHasFailure(DomainPurity.check("testdata/domain-purity/bad-forbidden-annotation"))),

        new TestCase("controller-placement/good", () -> assertNoFailures(ControllerPlacement.check("testdata/controller-placement/good"))),
        new TestCase("controller-placement/bad-outside-interfaces", () -> assertHasFailure(ControllerPlacement.check("testdata/controller-placement/bad-outside-interfaces"))),
        new TestCase("controller-placement/good-advice-outside-interfaces", () -> assertNoFailures(ControllerPlacement.check("testdata/controller-placement/good-advice-outside-interfaces"))),

        new TestCase("package-structure/good", () -> assertNoFailures(PackageStructure.check("testdata/package-structure/good"))),
        new TestCase("package-structure/bad-missing-layer", () -> assertHasFailure(PackageStructure.check("testdata/package-structure/bad-missing-layer"))),

        new TestCase("shared-infra/good", () -> assertNoFailures(SharedInfra.check("testdata/shared-infra/good"))),
        new TestCase("shared-infra/bad-outbox-missing-files", () -> assertHasFailure(SharedInfra.check("testdata/shared-infra/bad-outbox-missing-files"))),
        new TestCase("shared-infra/bad-task-queue-misplaced", () -> assertHasFailure(SharedInfra.check("testdata/shared-infra/bad-task-queue-misplaced"))),

        new TestCase("event-placement/good", () -> assertNoFailures(EventPlacement.check("testdata/event-placement/good"))),
        new TestCase("event-placement/bad-wrong-dir", () -> assertHasFailure(EventPlacement.check("testdata/event-placement/bad-wrong-dir"))),
        new TestCase("event-placement/good-outbox-exempt", () -> assertNoFailures(EventPlacement.check("testdata/event-placement/good-outbox-exempt"))),
        new TestCase("event-placement/good-integration-event", () -> assertNoFailures(EventPlacement.check("testdata/event-placement/good-integration-event"))),
        new TestCase("event-placement/bad-integration-event-wrong-dir", () -> assertHasFailure(EventPlacement.check("testdata/event-placement/bad-integration-event-wrong-dir"))),

        new TestCase("no-event-publisher-in-command/good", () -> assertNoFailures(NoEventPublisherInCommand.check("testdata/no-event-publisher-in-command/good"))),
        new TestCase("no-event-publisher-in-command/bad-has-publisher", () -> assertHasFailure(NoEventPublisherInCommand.check("testdata/no-event-publisher-in-command/bad-has-publisher"))),

        new TestCase("transaction-boundary/good", () -> assertNoFailures(TransactionBoundary.check("testdata/transaction-boundary/good"))),
        new TestCase("transaction-boundary/bad-has-transactional", () -> assertHasFailure(TransactionBoundary.check("testdata/transaction-boundary/bad-has-transactional"))),
        new TestCase("transaction-boundary/bad-repository-impl-missing-transactional", () -> assertHasFailure(TransactionBoundary.check("testdata/transaction-boundary/bad-repository-impl-missing-transactional"))),

        new TestCase("outbox-drain-order/good", () -> assertNoFailures(OutboxDrainOrder.check("testdata/outbox-drain-order/good"))),
        new TestCase("outbox-drain-order/bad-references-outbox-relay", () -> assertHasFailure(OutboxDrainOrder.check("testdata/outbox-drain-order/bad-references-outbox-relay"))),
        new TestCase("outbox-drain-order/bad-calls-poller-directly", () -> assertHasFailure(OutboxDrainOrder.check("testdata/outbox-drain-order/bad-calls-poller-directly"))),

        new TestCase("cqrs-query-purity/good", () -> assertNoFailures(CqrsQueryPurity.check("testdata/cqrs-query-purity/good"))),
        new TestCase("cqrs-query-purity/bad-repository-reference", () -> assertHasFailure(CqrsQueryPurity.check("testdata/cqrs-query-purity/bad-repository-reference"))),

        new TestCase("repository-naming/good", () -> assertNoFailures(RepositoryNaming.check("testdata/repository-naming/good"))),
        new TestCase("repository-naming/bad-findby", () -> assertHasFailure(RepositoryNaming.check("testdata/repository-naming/bad-findby"))),
        new TestCase("repository-naming/bad-findall", () -> assertHasFailure(RepositoryNaming.check("testdata/repository-naming/bad-findall"))),
        new TestCase("repository-naming/bad-count", () -> assertHasFailure(RepositoryNaming.check("testdata/repository-naming/bad-count"))),
        new TestCase("repository-naming/bad-bare-save", () -> assertHasFailure(RepositoryNaming.check("testdata/repository-naming/bad-bare-save"))),
        new TestCase("repository-naming/bad-bare-delete", () -> assertHasFailure(RepositoryNaming.check("testdata/repository-naming/bad-bare-delete")))
    );

    public static void main(String[] args) {
        int failures = 0;
        for (TestCase t : TESTS) {
            try {
                t.run().run();
                System.out.println("  PASS  " + t.name());
            } catch (AssertionError e) {
                failures++;
                System.out.println("  FAIL  " + t.name() + " — " + e.getMessage());
            }
        }
        System.out.println();
        if (failures == 0) {
            System.out.println(TESTS.size() + " passed  PASS");
        } else {
            System.out.println((TESTS.size() - failures) + " passed, " + failures + " failed  FAIL");
            System.exit(1);
        }
    }
}

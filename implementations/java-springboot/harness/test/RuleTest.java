// Per-rule regression tests — run against testdata/<rule>/good|bad-*/.
//
// No external test framework such as JUnit is pulled in; this is a lightweight runner
// written with nothing but its own assert methods that throw AssertionError on failure
// (the same idea as the nestjs harness's run-fixtures.ts). It assumes it's run from the
// test/ directory and uses the "testdata/..." relative paths as-is.
import harness.Kind;
import harness.RuleResult;
import harness.rules.AggregateIdFormat;
import harness.rules.AggregateNoPublicSetters;
import harness.rules.ControllerPlacement;
import harness.rules.CqrsQueryPurity;
import harness.rules.DockerfileConventions;
import harness.rules.DomainLayerIsolation;
import harness.rules.DomainPurity;
import harness.rules.ErrorResponseSchema;
import harness.rules.EventPlacement;
import harness.rules.FileNaming;
import harness.rules.InterfaceNoInfrastructure;
import harness.rules.NoCrossAggregateReference;
import harness.rules.NoCrossBcDomainImport;
import harness.rules.NoCrossBcRepositoryInApplication;
import harness.rules.NoDirectEnvAccessOutsideConfig;
import harness.rules.NoEventPublisherInCommand;
import harness.rules.NoGenericResponseKeys;
import harness.rules.NoLoggingInDomain;
import harness.rules.NoOrmAutoSyncInProdConfig;
import harness.rules.NoSilentCatch;
import harness.rules.OutboxDrainOrder;
import harness.rules.PackageStructure;
import harness.rules.QueryHandlerNoRawAggregate;
import harness.rules.RateLimitWired;
import harness.rules.RepositoryAnnotation;
import harness.rules.RepositoryNaming;
import harness.rules.SchedulerInInfrastructureOnly;
import harness.rules.ServiceAnnotation;
import harness.rules.SharedInfra;
import harness.rules.SoftDeleteFilter;
import harness.rules.TransactionBoundary;
import harness.rules.TypedErrorsOnly;

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
        new TestCase("repository-naming/bad-bare-delete", () -> assertHasFailure(RepositoryNaming.check("testdata/repository-naming/bad-bare-delete"))),
        new TestCase("repository-naming/bad-update", () -> assertHasFailure(RepositoryNaming.check("testdata/repository-naming/bad-update"))),

        new TestCase("domain-layer-isolation/good", () -> assertNoFailures(DomainLayerIsolation.check("testdata/domain-layer-isolation/good"))),
        new TestCase("domain-layer-isolation/bad-imports-application", () -> assertHasFailure(DomainLayerIsolation.check("testdata/domain-layer-isolation/bad-imports-application"))),
        new TestCase("domain-layer-isolation/bad-imports-sibling-infrastructure", () -> assertHasFailure(DomainLayerIsolation.check("testdata/domain-layer-isolation/bad-imports-sibling-infrastructure"))),

        new TestCase("interface-no-infrastructure/good", () -> assertNoFailures(InterfaceNoInfrastructure.check("testdata/interface-no-infrastructure/good"))),
        new TestCase("interface-no-infrastructure/bad-imports-infrastructure", () -> assertHasFailure(InterfaceNoInfrastructure.check("testdata/interface-no-infrastructure/bad-imports-infrastructure"))),

        new TestCase("aggregate-no-public-setters/good", () -> assertNoFailures(AggregateNoPublicSetters.check("testdata/aggregate-no-public-setters/good"))),
        new TestCase("aggregate-no-public-setters/bad-javabean-setter", () -> assertHasFailure(AggregateNoPublicSetters.check("testdata/aggregate-no-public-setters/bad-javabean-setter"))),
        new TestCase("aggregate-no-public-setters/bad-lombok-setter", () -> assertHasFailure(AggregateNoPublicSetters.check("testdata/aggregate-no-public-setters/bad-lombok-setter"))),

        new TestCase("no-cross-aggregate-reference/good", () -> assertNoFailures(NoCrossAggregateReference.check("testdata/no-cross-aggregate-reference/good"))),
        new TestCase("no-cross-aggregate-reference/bad-payment-references-refund", () -> assertHasFailure(NoCrossAggregateReference.check("testdata/no-cross-aggregate-reference/bad-payment-references-refund"))),

        new TestCase("no-direct-env-access-outside-config/good", () -> assertNoFailures(NoDirectEnvAccessOutsideConfig.check("testdata/no-direct-env-access-outside-config/good"))),
        new TestCase("no-direct-env-access-outside-config/bad-getenv-in-application", () -> assertHasFailure(NoDirectEnvAccessOutsideConfig.check("testdata/no-direct-env-access-outside-config/bad-getenv-in-application"))),

        new TestCase("no-cross-bc-repository-in-application/good", () -> assertNoFailures(NoCrossBcRepositoryInApplication.check("testdata/no-cross-bc-repository-in-application/good"))),
        new TestCase("no-cross-bc-repository-in-application/bad-cross-domain-repository", () -> assertHasFailure(NoCrossBcRepositoryInApplication.check("testdata/no-cross-bc-repository-in-application/bad-cross-domain-repository"))),
        new TestCase("no-cross-bc-repository-in-application/bad-cross-domain-query", () -> assertHasFailure(NoCrossBcRepositoryInApplication.check("testdata/no-cross-bc-repository-in-application/bad-cross-domain-query"))),

        new TestCase("no-logging-in-domain/good", () -> assertNoFailures(NoLoggingInDomain.check("testdata/no-logging-in-domain/good"))),
        new TestCase("no-logging-in-domain/bad-slf4j-in-domain", () -> assertHasFailure(NoLoggingInDomain.check("testdata/no-logging-in-domain/bad-slf4j-in-domain"))),

        new TestCase("scheduler-in-infrastructure-only/good", () -> assertNoFailures(SchedulerInInfrastructureOnly.check("testdata/scheduler-in-infrastructure-only/good"))),
        new TestCase("scheduler-in-infrastructure-only/bad-scheduled-in-application", () -> assertHasFailure(SchedulerInInfrastructureOnly.check("testdata/scheduler-in-infrastructure-only/bad-scheduled-in-application"))),

        new TestCase("no-silent-catch/good", () -> assertNoFailures(NoSilentCatch.check("testdata/no-silent-catch/good"))),
        new TestCase("no-silent-catch/bad-empty-catch", () -> assertHasFailure(NoSilentCatch.check("testdata/no-silent-catch/bad-empty-catch"))),

        new TestCase("dockerfile-conventions/good", () -> assertNoFailures(DockerfileConventions.check("testdata/dockerfile-conventions/good"))),
        new TestCase("dockerfile-conventions/bad-single-stage-no-healthcheck", () -> assertHasFailure(DockerfileConventions.check("testdata/dockerfile-conventions/bad-single-stage-no-healthcheck"))),
        new TestCase("dockerfile-conventions/bad-no-user", () -> assertHasFailure(DockerfileConventions.check("testdata/dockerfile-conventions/bad-no-user"))),

        new TestCase("aggregate-id-format/good", () -> assertNoFailures(AggregateIdFormat.check("testdata/aggregate-id-format/good"))),
        new TestCase("aggregate-id-format/bad-raw-uuid", () -> assertHasFailure(AggregateIdFormat.check("testdata/aggregate-id-format/bad-raw-uuid"))),

        new TestCase("error-response-schema/good", () -> assertNoFailures(ErrorResponseSchema.check("testdata/error-response-schema/good"))),
        new TestCase("error-response-schema/bad-missing-field", () -> assertHasFailure(ErrorResponseSchema.check("testdata/error-response-schema/bad-missing-field"))),
        new TestCase("error-response-schema/bad-extra-field", () -> assertHasFailure(ErrorResponseSchema.check("testdata/error-response-schema/bad-extra-field"))),

        new TestCase("soft-delete-filter/good", () -> assertNoFailures(SoftDeleteFilter.check("testdata/soft-delete-filter/good"))),
        new TestCase("soft-delete-filter/bad-missing-filter", () -> assertHasFailure(SoftDeleteFilter.check("testdata/soft-delete-filter/bad-missing-filter"))),
        new TestCase("soft-delete-filter/good-global-annotation", () -> assertNoFailures(SoftDeleteFilter.check("testdata/soft-delete-filter/good-global-annotation"))),

        new TestCase("typed-errors-only/good", () -> assertNoFailures(TypedErrorsOnly.check("testdata/typed-errors-only/good"))),
        new TestCase("typed-errors-only/bad-runtime-exception", () -> assertHasFailure(TypedErrorsOnly.check("testdata/typed-errors-only/bad-runtime-exception"))),
        new TestCase("typed-errors-only/bad-illegal-state", () -> assertHasFailure(TypedErrorsOnly.check("testdata/typed-errors-only/bad-illegal-state"))),

        new TestCase("rate-limit-wired/good", () -> assertNoFailures(RateLimitWired.check("testdata/rate-limit-wired/good"))),
        new TestCase("rate-limit-wired/bad-hardcoded", () -> assertHasFailure(RateLimitWired.check("testdata/rate-limit-wired/bad-hardcoded"))),
        new TestCase("rate-limit-wired/bad-not-component", () -> assertHasFailure(RateLimitWired.check("testdata/rate-limit-wired/bad-not-component"))),
        new TestCase("rate-limit-wired/bad-disabled", () -> assertHasFailure(RateLimitWired.check("testdata/rate-limit-wired/bad-disabled"))),

        new TestCase("no-generic-response-keys/good", () -> assertNoFailures(NoGenericResponseKeys.check("testdata/no-generic-response-keys/good"))),
        new TestCase("no-generic-response-keys/bad-data-field", () -> assertHasFailure(NoGenericResponseKeys.check("testdata/no-generic-response-keys/bad-data-field"))),
        new TestCase("no-generic-response-keys/bad-items-field", () -> assertHasFailure(NoGenericResponseKeys.check("testdata/no-generic-response-keys/bad-items-field"))),

        new TestCase("query-handler-no-raw-aggregate/good", () -> assertNoFailures(QueryHandlerNoRawAggregate.check("testdata/query-handler-no-raw-aggregate/good"))),
        new TestCase("query-handler-no-raw-aggregate/bad-service-returns-raw-aggregate", () -> assertHasFailure(QueryHandlerNoRawAggregate.check("testdata/query-handler-no-raw-aggregate/bad-service-returns-raw-aggregate"))),
        new TestCase("query-handler-no-raw-aggregate/bad-controller-returns-raw-aggregate", () -> assertHasFailure(QueryHandlerNoRawAggregate.check("testdata/query-handler-no-raw-aggregate/bad-controller-returns-raw-aggregate"))),

        new TestCase("no-cross-bc-domain-import/good", () -> assertNoFailures(NoCrossBcDomainImport.check("testdata/no-cross-bc-domain-import/good"))),
        new TestCase("no-cross-bc-domain-import/bad-card-imports-payment-domain", () -> assertHasFailure(NoCrossBcDomainImport.check("testdata/no-cross-bc-domain-import/bad-card-imports-payment-domain"))),

        new TestCase("no-orm-autosync-in-prod-config/good", () -> assertNoFailures(NoOrmAutoSyncInProdConfig.check("testdata/no-orm-autosync-in-prod-config/good"))),
        new TestCase("no-orm-autosync-in-prod-config/bad-prod-update", () -> assertHasFailure(NoOrmAutoSyncInProdConfig.check("testdata/no-orm-autosync-in-prod-config/bad-prod-update"))),
        new TestCase("no-orm-autosync-in-prod-config/bad-default-update", () -> assertHasFailure(NoOrmAutoSyncInProdConfig.check("testdata/no-orm-autosync-in-prod-config/bad-default-update")))
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

package harness;

// Spring Boot Harness — checks Java project structure/annotation rules
// Usage: java -jar build/harness.jar <projectRoot>  (or use the harness.sh wrapper)
//
// Each rule is implemented in its own class under rules/, and each returns a RuleResult —
// this file only defines the rule list and serves as the CLI entry point that
// aggregates and prints the results.
// Per-rule regression tests are verified via test/RuleTest.java + test/testdata/<rule>/
// fixtures (see README.md).

import harness.rules.AggregateIdFormat;
import harness.rules.AggregateNoPublicSetters;
import harness.rules.ApiDocumentation;
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

import java.util.List;

public final class Main {
    private Main() {
    }

    static final List<Rule> RULES = List.of(
        FileNaming::check,
        RepositoryAnnotation::check,
        ServiceAnnotation::check,
        DomainPurity::check,
        ControllerPlacement::check,
        PackageStructure::check,
        SharedInfra::check,
        EventPlacement::check,
        NoEventPublisherInCommand::check,
        TransactionBoundary::check,
        OutboxDrainOrder::check,
        CqrsQueryPurity::check,
        RepositoryNaming::check,
        DomainLayerIsolation::check,
        InterfaceNoInfrastructure::check,
        AggregateNoPublicSetters::check,
        NoCrossAggregateReference::check,
        NoDirectEnvAccessOutsideConfig::check,
        NoCrossBcRepositoryInApplication::check,
        NoLoggingInDomain::check,
        SchedulerInInfrastructureOnly::check,
        NoSilentCatch::check,
        DockerfileConventions::check,
        AggregateIdFormat::check,
        ErrorResponseSchema::check,
        SoftDeleteFilter::check,
        TypedErrorsOnly::check,
        RateLimitWired::check,
        NoGenericResponseKeys::check,
        QueryHandlerNoRawAggregate::check,
        NoCrossBcDomainImport::check,
        NoOrmAutoSyncInProdConfig::check,
        ApiDocumentation::check
    );

    public static void main(String[] args) {
        String root = args.length > 0 ? args[0] : ".";

        int passCount = 0;
        int failCount = 0;
        for (Rule rule : RULES) {
            RuleResult result = rule.apply(root);
            System.out.println("\n[" + result.section + "]");
            for (Finding finding : result.findings) {
                switch (finding.kind) {
                    case PASS -> {
                        passCount++;
                        System.out.println("  PASS  " + finding.name);
                    }
                    case FAIL -> {
                        failCount++;
                        System.out.println("  FAIL  " + finding.name + " — " + finding.reason);
                    }
                    case SKIP -> System.out.println("  SKIP  " + finding.name);
                }
            }
        }

        System.out.println("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        if (failCount == 0) {
            System.out.println(passCount + " passed  PASS");
        } else {
            System.out.println(passCount + " passed, " + failCount + " failed  FAIL");
            System.exit(1);
        }
    }
}

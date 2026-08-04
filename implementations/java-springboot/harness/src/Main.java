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
import harness.rules.UtcTimestampSource;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class Main {
    private Main() {
    }

    // ruleMaxScore is the fixed per-rule point budget. A rule contributes
    // ruleMaxScore * (passCount / (passCount+failCount)) to the raw score when it produced
    // at least one non-SKIP finding, and 0 (excluded from the normalization denominator)
    // when every finding was SKIP — mirroring the nestjs harness's "maxScore = 0 means not
    // applicable" convention.
    private static final int RULE_MAX_SCORE = 20;

    private enum Category { STRUCTURE, ARCHITECTURE, RUNTIME, TESTING, API, SEMANTICS }

    // Assigns a rule's section name to one of the nestjs harness's 6 score-breakdown
    // categories, reusing the same substring-matching approach as
    // evaluators/shared/score.ts so results stay comparable across languages. Returns null
    // for rules with no clear category (also mirrors nestjs, where not every evaluator
    // lands in a bucket — e.g. file-naming).
    private static Category bucketFor(String name) {
        if (name.contains("structure")) return Category.STRUCTURE;
        if (name.contains("layer")
            || name.contains("repository")
            || name.contains("cqrs")
            || name.contains("scheduler")
            || name.contains("domain-purity")
            || name.contains("interface-no-infrastructure")
            || name.contains("no-cross-aggregate-reference")
            || name.contains("no-cross-bc-domain-import")
            || name.contains("soft-delete-filter")
            || name.contains("query-handler-no-raw-aggregate")
            || name.contains("outbox")
            || name.contains("shared-infra")
            || name.contains("no-direct-env-access")
            || name.contains("aggregate-id")
            || name.contains("aggregate-no-public-setters")
            || name.contains("logging")
            || name.contains("error-response-schema")
            || name.contains("typed-errors-only")
            || name.contains("no-silent-catch")
            || name.contains("no-event-publisher-in-command")
            || name.contains("service-annotation")
            || name.contains("transaction-boundary")
            || name.contains("utc-timestamp-source")) return Category.ARCHITECTURE;
        if (name.contains("dockerfile") || name.contains("no-orm-autosync-in-prod-config")) return Category.RUNTIME;
        if (name.contains("no-generic-response-keys")
            || name.contains("api-documentation")
            || name.contains("rate-limit-wired")
            || name.contains("controller-placement")) return Category.API;
        return null;
    }

    private static String gradeFor(int total) {
        if (total >= 90) return "A";
        if (total >= 80) return "B";
        if (total >= 70) return "C";
        if (total >= 60) return "D";
        return "F";
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
        ApiDocumentation::check,
        UtcTimestampSource::check
    );

    public static void main(String[] args) {
        String root = args.length > 0 ? args[0] : ".";

        int passCount = 0;
        int failCount = 0;
        double rawScore = 0;
        int rawMax = 0;
        int skippedRules = 0;
        Map<Category, Double> breakdown = new EnumMap<>(Category.class);
        Map<Category, Integer> breakdownMax = new EnumMap<>(Category.class);

        for (Rule rule : RULES) {
            RuleResult result = rule.apply(root);
            System.out.println("\n[" + result.section + "]");

            int rulePass = 0;
            int ruleFail = 0;
            for (Finding finding : result.findings) {
                switch (finding.kind) {
                    case PASS -> {
                        passCount++;
                        rulePass++;
                        System.out.println("  PASS  " + finding.name);
                    }
                    case FAIL -> {
                        failCount++;
                        ruleFail++;
                        System.out.println("  FAIL  " + finding.name + " — " + finding.reason);
                    }
                    case SKIP -> System.out.println("  SKIP  " + finding.name);
                }
            }

            if (rulePass + ruleFail == 0) {
                skippedRules++;
                continue;
            }

            double ruleScore = RULE_MAX_SCORE * (double) rulePass / (rulePass + ruleFail);
            rawScore += ruleScore;
            rawMax += RULE_MAX_SCORE;

            Category category = bucketFor(result.section);
            if (category != null) {
                breakdown.merge(category, ruleScore, Double::sum);
                breakdownMax.merge(category, RULE_MAX_SCORE, Integer::sum);
            }
        }

        int total = rawMax > 0 ? (int) Math.round(rawScore / rawMax * 100) : 0;

        System.out.println("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.printf(
            "%s (%d/100, raw %.1f/%d) — %d failure(s) across %d evaluator(s), %d skipped (not applicable)%n",
            gradeFor(total), total, rawScore, rawMax, failCount, RULES.size(), skippedRules
        );
        for (Category category : Category.values()) {
            int max = breakdownMax.getOrDefault(category, 0);
            if (max > 0) {
                System.out.printf("  %-13s %.1f/%d%n", category.name().toLowerCase(), breakdown.get(category), max);
            }
        }

        if (failCount == 0) {
            System.out.println(passCount + " passed  PASS");
        } else {
            System.out.println(passCount + " passed, " + failCount + " failed  FAIL");
            System.exit(1);
        }
    }
}

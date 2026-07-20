package harness;

// Spring Boot Harness — Java 프로젝트 구조·어노테이션 규칙 검사
// Usage: java -jar build/harness.jar <projectRoot>  (또는 harness.sh 래퍼 사용)
//
// 각 규칙은 rules/ 아래 별도 클래스에 구현되어 있고, 각각 RuleResult를 반환한다 —
// 이 파일은 규칙 목록을 정의하고 결과를 집계·출력하는 CLI 진입점 역할만 한다.
// 규칙별 회귀 테스트는 test/RuleTest.java + test/testdata/<rule>/ fixture로 검증한다
// (README.md 참고).

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
        NoOrmAutoSyncInProdConfig::check
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

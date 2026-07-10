package harness;

// Spring Boot Harness — Java 프로젝트 구조·어노테이션 규칙 검사
// Usage: java -jar build/harness.jar <projectRoot>  (또는 harness.sh 래퍼 사용)
//
// 각 규칙은 rules/ 아래 별도 클래스에 구현되어 있고, 각각 RuleResult를 반환한다 —
// 이 파일은 규칙 목록을 정의하고 결과를 집계·출력하는 CLI 진입점 역할만 한다.
// 규칙별 회귀 테스트는 test/RuleTest.java + test/testdata/<rule>/ fixture로 검증한다
// (README.md 참고).

import harness.rules.ControllerPlacement;
import harness.rules.DomainPurity;
import harness.rules.EventPlacement;
import harness.rules.FileNaming;
import harness.rules.NoEventPublisherInCommand;
import harness.rules.OutboxDrainOrder;
import harness.rules.PackageStructure;
import harness.rules.RepositoryAnnotation;
import harness.rules.ServiceAnnotation;
import harness.rules.SharedInfra;
import harness.rules.TransactionBoundary;

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
        OutboxDrainOrder::check
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

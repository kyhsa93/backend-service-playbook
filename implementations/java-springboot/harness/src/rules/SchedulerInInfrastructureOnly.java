package harness.rules;

import harness.Finding;
import harness.RuleResult;

import java.io.File;
import java.util.regex.Pattern;

import static harness.JavaFiles.collectJavaFiles;
import static harness.JavaFiles.pathContains;
import static harness.JavaFiles.readText;
import static harness.JavaFiles.relTo;

/**
 * [20] {@code @Scheduled}/{@code @EnableScheduling}은 domain/·application/에서 쓸 수 없다
 * (scheduling.md — "Scheduler는 Infrastructure 레이어: application/이 아니라 infrastructure/에
 * 둔다", "Application/Domain에 @Scheduled 사용 금지").
 *
 * <p>블록리스트 방식이다 — "infrastructure/ 안에서만 허용"이라는 화이트리스트로 만들면, 이
 * 저장소의 실제 합법적인 두 사용처가 오탐된다: {@code outbox/OutboxPoller.java}(공용 인프라
 * 패키지지만 도메인별 {@code infrastructure/} 하위가 아니라 최상위 {@code outbox/}에 있음)와
 * {@code AccountServiceApplication.java}(부트스트랩 진입점, {@code @EnableScheduling}이 필연적으로
 * 최상위 패키지에 위치)다. 둘 다 domain/·application/ 밖에 있으므로 이 규칙을 통과해야 하고,
 * 블록리스트 방식이 정확히 그 결과를 낸다.
 */
public final class SchedulerInInfrastructureOnly {
    private SchedulerInInfrastructureOnly() {
    }

    private static final Pattern SCHEDULER_USAGE = Pattern.compile("@Scheduled\\b|@EnableScheduling\\b");

    public static RuleResult check(String rootPath) {
        File root = new File(rootPath);
        RuleResult result = new RuleResult("scheduler-in-infrastructure-only");
        boolean found = false;

        for (File f : collectJavaFiles(root)) {
            boolean inScope = pathContains(f, "/domain/") || pathContains(f, "/application/");
            if (!inScope) continue;
            found = true;
            String rel = relTo(f, root);
            String content = readText(f);

            if (SCHEDULER_USAGE.matcher(content).find()) {
                result.add(Finding.fail(rel,
                    "domain/ 또는 application/에서 @Scheduled/@EnableScheduling 사용 금지 — Scheduler는 infrastructure/에 배치해야 함(scheduling.md)"));
            } else {
                result.add(Finding.pass(rel + " (스케줄러 미사용 확인)"));
            }
        }

        if (!found) result.add(Finding.skip("domain/ 또는 application/ Java 파일 없음"));
        return result;
    }
}

package harness.rules;

import harness.Finding;
import harness.RuleResult;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

import static harness.JavaFiles.collectJavaFiles;
import static harness.JavaFiles.nameWithoutExtension;
import static harness.JavaFiles.pathContains;
import static harness.JavaFiles.readText;
import static harness.JavaFiles.relTo;

/** [8] event-placement */
public final class EventPlacement {
    private EventPlacement() {
    }

    public static RuleResult check(String rootPath) {
        File root = new File(rootPath);
        RuleResult result = new RuleResult("event-placement");
        boolean found = false;
        Set<String> reported = new HashSet<>();

        for (File f : collectJavaFiles(root)) {
            String name = nameWithoutExtension(f);
            String rel = relTo(f, root);
            if (name.endsWith("EventHandler")) {
                found = true;
                reported.add(f.getPath());
                // Outbox 패턴의 디스패치 계약(OutboxEventHandler 등)은 도메인별 핸들러가 아니라
                // OutboxRelay와 함께 동작하는 공용 인프라이므로 outbox/ 패키지 배치도 허용한다
                // (domain-events.md, shared-modules.md 참고).
                if (pathContains(f, "/application/event/") || pathContains(f, "/outbox/")) {
                    result.add(Finding.pass(rel + " (EventHandler)"));
                } else {
                    result.add(Finding.fail(rel, "EventHandler는 application/event/(도메인별 핸들러) 또는 outbox/(Outbox 디스패치 계약) 패키지 안에 있어야 함"));
                }
            } else if (name.matches(".*IntegrationEvent(V\\d+)?$")) {
                found = true;
                reported.add(f.getPath());
                if (pathContains(f, "/application/integrationevent/")) {
                    result.add(Finding.pass(rel + " (IntegrationEvent)"));
                } else {
                    result.add(Finding.fail(rel, "IntegrationEvent는 application/integrationevent/ 패키지 안에 있어야 함"));
                }
            }
        }

        // @EventListener — Spring ApplicationEventPublisher 기반 동기 도메인 이벤트 구독.
        // 파일명이 *EventHandler/*IntegrationEvent 규칙을 따르지 않더라도 @EventListener
        // 애노테이션이 있으면 실질적인 이벤트 핸들러이므로 동일하게 application/event/ 배치
        // 규칙을 적용한다.
        for (File f : collectJavaFiles(root)) {
            if (reported.contains(f.getPath())) continue;
            String content = readText(f);
            if (!content.contains("@EventListener")) continue;
            found = true;
            String rel = relTo(f, root);
            if (pathContains(f, "/application/event/")) {
                result.add(Finding.pass(rel + " (@EventListener)"));
            } else {
                result.add(Finding.fail(rel, "@EventListener 사용 클래스는 application/event/ 패키지 안에 있어야 함"));
            }
        }

        if (!found) result.add(Finding.skip("이벤트 핸들러 없음"));
        return result;
    }
}

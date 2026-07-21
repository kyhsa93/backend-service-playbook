package harness.rules;

import harness.Finding;
import harness.RuleResult;

import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static harness.JavaFiles.collectJavaFiles;
import static harness.JavaFiles.pathContains;
import static harness.JavaFiles.readText;
import static harness.JavaFiles.relTo;

/**
 * [11] Outbox 동기 드레인 금지 — Command Service는 OutboxRelay/OutboxPoller/OutboxConsumer를 직접
 * 참조하거나 드레인을 호출하지 않아야 한다 (domain-events.md)
 *
 * Outbox → 큐 발행/수신은 독립적으로 주기 실행되는 {@code OutboxPoller}(@Scheduled)와 {@code
 * OutboxConsumer}(SQS long polling)만의 책임이다. Command Service가 저장(save&lt;Noun&gt;) 커밋 직후
 * 같은 프로세스 안에서 드레인을 동기 호출하면, Outbox 패턴이 원래 분리하려던 "쓰기"와 "이벤트 처리"가
 * 다시 한 요청 안에 묶여버린다 — 이 검사가 없으면 누군가 Command Service에 드레인 호출을 추가해도
 * 잡아내지 못한다.
 *
 * 주석까지 검사 대상에 포함하면 "왜 호출하면 안 되는지"를 설명하는 코드 주석 자체가 오탐될 수 있으므로,
 * 아주 단순한 주석 제거 후 검사한다(이 harness의 다른 정규식 기반 규칙들과 동일한 수준의 근사치).
 */
public final class OutboxDrainOrder {
    private OutboxDrainOrder() {
    }

    private static final Pattern FORBIDDEN_SYMBOL =
        Pattern.compile("\\bOutboxRelay\\b|\\bOutboxPoller\\b|\\bOutboxConsumer\\b");
    private static final Pattern FORBIDDEN_CALL =
        Pattern.compile("\\.\\s*(?:processPending|poll|drainOnce)\\s*\\(");

    public static RuleResult check(String rootPath) {
        File root = new File(rootPath);
        RuleResult result = new RuleResult("outbox-drain-order");
        boolean found = false;
        for (File f : collectJavaFiles(root)) {
            if (!pathContains(f, "/application/command/")) continue;
            found = true;
            String rel = relTo(f, root);
            String content = stripComments(readText(f));
            Matcher symbolMatcher = FORBIDDEN_SYMBOL.matcher(content);
            Matcher callMatcher = FORBIDDEN_CALL.matcher(content);
            if (symbolMatcher.find() || callMatcher.find()) {
                result.add(Finding.fail(rel, "OutboxRelay/OutboxPoller/OutboxConsumer를 직접 참조하거나 processPending()/poll()/drainOnce()류를 호출함 — Command Service는 저장 후 곧바로 반환해야 하며, Outbox → 큐 발행/수신은 독립적으로 주기 실행되는 OutboxPoller/OutboxConsumer만의 책임이다(동기 드레인 금지, domain-events.md)"));
            } else {
                result.add(Finding.pass(rel + " (동기 드레인 참조 없음 확인)"));
            }
        }
        if (!found) result.add(Finding.skip("application/command/ 하위 Command Service 없음"));
        return result;
    }

    // 매우 단순한 주석 제거 — 이 harness의 다른 정규식 기반 규칙과 동일한 수준의 근사치다. "왜
    // OutboxPoller를 호출하면 안 되는지"를 설명하는 코드 주석 자체가 위반으로 오탐되는 것을 막는다.
    private static String stripComments(String content) {
        return content.replaceAll("/\\*[\\s\\S]*?\\*/", "").replaceAll("//.*", "");
    }
}

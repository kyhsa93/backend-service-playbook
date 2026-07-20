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
 * [15] Aggregate는 JavaBean 스타일 setter를 가질 수 없다 — 상태 변경은 항상 이름 있는 도메인
 * 메서드({@code deposit()}/{@code suspend()} 등)로만 해야 한다(tactical-ddd.md).
 *
 * <p>이 저장소의 Aggregate({@code Account}/{@code Card}/{@code Payment}/{@code Refund})는 이미
 * plain class + private 생성자 + 정적 팩토리({@code create}/{@code reconstitute}) + 이름 있는 도메인
 * 메서드로만 상태를 바꾸므로, 현재는 위반이 구조적으로 존재하지 않는다 — 이 규칙은 회귀 가드다: 누군가
 * 나중에 Aggregate를 Lombok {@code @Setter}나 JavaBean {@code public void setX(...)} 메서드가 있는
 * 가변 클래스로 바꾸는 것을 잡아낸다.
 *
 * <p>{@code record}(불변, 필드별 setter 자체가 존재하지 않음)는 애초에 검사 대상에서 자연히 제외된다 —
 * {@code class} 선언이 있는 파일만 본다. domain/ 안의 모든 class를 "Aggregate"로 간주하는 것은
 * 과잉 일반화일 수 있으나(Value Object도 class로 있을 수 있음), setter는 어느 쪽이든 금지되는
 * 패턴이라 domain/ 전체(class 선언 파일)를 대상으로 해도 오탐 위험이 없다 — 블록리스트 방식.
 */
public final class AggregateNoPublicSetters {
    private AggregateNoPublicSetters() {
    }

    private static final Pattern CLASS_DECL = Pattern.compile("\\bclass\\s+(\\w+)");
    private static final Pattern PUBLIC_SETTER = Pattern.compile("\\bpublic\\s+void\\s+set[A-Z]\\w*\\s*\\(");
    private static final Pattern LOMBOK_SETTER = Pattern.compile("@Setter\\b");

    public static RuleResult check(String rootPath) {
        File root = new File(rootPath);
        RuleResult result = new RuleResult("aggregate-no-public-setters");
        boolean found = false;

        for (File f : collectJavaFiles(root)) {
            if (!pathContains(f, "/domain/")) continue;
            String code = stripComments(readText(f));
            Matcher classMatcher = CLASS_DECL.matcher(code);
            if (!classMatcher.find()) continue; // interface/record/enum — 검사 대상 아님(record는 setter 자체가 없음)

            found = true;
            String rel = relTo(f, root);

            if (LOMBOK_SETTER.matcher(code).find()) {
                result.add(Finding.fail(rel,
                    "domain/ class에 Lombok @Setter 사용 금지 — 상태 변경은 이름 있는 도메인 메서드로만(tactical-ddd.md)"));
                continue;
            }

            Matcher setterMatcher = PUBLIC_SETTER.matcher(code);
            if (setterMatcher.find()) {
                result.add(Finding.fail(rel,
                    "domain/ class에 JavaBean 스타일 public setter 금지 — 상태 변경은 이름 있는 도메인 메서드로만(tactical-ddd.md)"));
            } else {
                result.add(Finding.pass(rel + " (setter 없음 확인)"));
            }
        }

        if (!found) result.add(Finding.skip("domain/ 안에 class 선언 파일 없음(record/interface/enum만 존재)"));
        return result;
    }

    private static String stripComments(String content) {
        return content.replaceAll("/\\*[\\s\\S]*?\\*/", "").replaceAll("//.*", "");
    }
}

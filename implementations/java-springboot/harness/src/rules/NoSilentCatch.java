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
 * [21] application/·infrastructure/에서 빈 catch 블록(로깅도 재throw도 없는 {@code catch (...) {}})
 * 금지 — 실패가 조용히 사라지면 관찰 불가능해진다(observability.md — "에러는 반드시 로깅한 뒤
 * 예외를 던지거나 응답한다").
 *
 * <p>주석만 담긴 catch 블록도(코드 없이) 여전히 조용한 실패이므로 잡아낸다 — 먼저 주석을 제거한 뒤
 * "catch (...) { }" 형태(공백만 존재)만 매칭한다. 의도적으로 매우 좁게 잡는다: 블록 안에 어떤 코드
 * (로깅 호출이든, 무시한다는 이유를 설명하는 변수 대입이든)라도 있으면 이 규칙은 관여하지 않는다 —
 * "로깅 없는 catch"처럼 폭넓게 잡으면 이미 합법적으로 존재하는 재throw-only catch 등을 오탐할
 * 위험이 커서, 가장 명백한 패턴(완전히 빈 블록)만 블록리스트로 잡는다. 이 저장소의 현재
 * application/·infrastructure/ 코드에는 이 패턴이 없음을 확인했다 — 순수 회귀 가드다.
 */
public final class NoSilentCatch {
    private NoSilentCatch() {
    }

    private static final Pattern EMPTY_CATCH = Pattern.compile("catch\\s*\\([^)]*\\)\\s*\\{\\s*\\}");

    public static RuleResult check(String rootPath) {
        File root = new File(rootPath);
        RuleResult result = new RuleResult("no-silent-catch");
        boolean found = false;

        for (File f : collectJavaFiles(root)) {
            boolean inScope = pathContains(f, "/application/") || pathContains(f, "/infrastructure/");
            if (!inScope) continue;
            found = true;
            String rel = relTo(f, root);
            String code = stripComments(readText(f));

            if (EMPTY_CATCH.matcher(code).find()) {
                result.add(Finding.fail(rel,
                    "빈 catch 블록 금지 — 예외를 무시하지 말고 로깅 후 처리하거나 재throw해야 함(observability.md)"));
            } else {
                result.add(Finding.pass(rel + " (빈 catch 블록 없음 확인)"));
            }
        }

        if (!found) result.add(Finding.skip("application/ 또는 infrastructure/ Java 파일 없음"));
        return result;
    }

    private static String stripComments(String content) {
        return content.replaceAll("/\\*[\\s\\S]*?\\*/", "").replaceAll("//.*", "");
    }
}

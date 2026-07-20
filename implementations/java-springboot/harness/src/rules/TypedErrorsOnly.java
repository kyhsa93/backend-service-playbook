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
 * [27] 타입화된 에러만 허용 — 루트 절대 원칙(AGENTS.md): "에러는 enum으로 타입화 — free-form 문자열
 * 금지". 이 저장소의 관용구는 도메인별 타입화 예외 클래스({@code AccountException}, {@code
 * PaymentException}, {@code CardException}, {@code AuthException} 등, 내부에 {@code ErrorCode}
 * enum 보유)를 던지고 {@code @ExceptionHandler}가 변환하는 방식이다(error-handling.md).
 *
 * <p>{@code domain/}·{@code application/}에서 일반 예외 클래스({@code RuntimeException}/{@code
 * IllegalStateException}/{@code IllegalArgumentException}/{@code UnsupportedOperationException}/
 * {@code Exception})를 문자열 메시지와 함께 직접 생성해 던지면 실패로 잡는다 — 타입화된 ErrorCode
 * 없이 자유 형식 문자열만으로 에러를 표현하는 셈이라 원칙 위반이다.
 *
 * <p>블록리스트를 일반 예외 클래스 이름으로만 좁게 잡는다({@code AccountException}처럼 도메인
 * 특화 이름으로 끝나는 클래스는 매칭되지 않는다) — 프레임워크가 요구하는 정당한 generic 예외
 * 사용(예: 파싱 실패 시 {@code IllegalStateException}을 던지는 인프라 코드)까지 넓게 잡지 않도록,
 * domain/·application/에만 스코프를 한정한다. 현재 이 저장소의 domain/·application/ 코드에는 이
 * 패턴이 없음을 확인했다 — outbox/(OutboxWriter/OutboxConsumer)·common/config/
 * (SecretsEnvironmentPostProcessor)의 {@code IllegalStateException}은 infrastructure 성격의
 * fail-fast/역직렬화 오류이며 비즈니스 규칙 위반이 아니라 스코프 밖이다. 순수 회귀 가드다.
 */
public final class TypedErrorsOnly {
    private TypedErrorsOnly() {
    }

    private static final Pattern GENERIC_THROW = Pattern.compile(
        "throw\\s+new\\s+(RuntimeException|IllegalStateException|IllegalArgumentException|UnsupportedOperationException|Exception)\\s*\\(");

    public static RuleResult check(String rootPath) {
        File root = new File(rootPath);
        RuleResult result = new RuleResult("typed-errors-only");
        boolean found = false;

        for (File f : collectJavaFiles(root)) {
            boolean inScope = pathContains(f, "/domain/") || pathContains(f, "/application/");
            if (!inScope) continue;
            found = true;
            String rel = relTo(f, root);
            String code = stripComments(readText(f));

            Matcher m = GENERIC_THROW.matcher(code);
            boolean violation = false;
            while (m.find()) {
                violation = true;
                result.add(Finding.fail(rel,
                    "'" + m.group(1) + "'를 문자열과 함께 직접 throw — free-form 문자열 대신 타입화된 예외(도메인별 <Domain>Exception + ErrorCode enum)를 던져야 함(error-handling.md, AGENTS.md)"));
            }
            if (!violation) {
                result.add(Finding.pass(rel + " (일반 예외 직접 throw 없음 확인)"));
            }
        }

        if (!found) result.add(Finding.skip("domain/ 또는 application/ Java 파일 없음"));
        return result;
    }

    private static String stripComments(String content) {
        return content.replaceAll("/\\*[\\s\\S]*?\\*/", "").replaceAll("//.*", "");
    }
}

package harness.rules;

import harness.Finding;
import harness.RuleResult;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static harness.JavaFiles.collectJavaFiles;
import static harness.JavaFiles.nameWithoutExtension;
import static harness.JavaFiles.pathContains;
import static harness.JavaFiles.readText;
import static harness.JavaFiles.relTo;

/**
 * [30] Query Service/Controller가 raw Aggregate를 직접 반환하면 실패 — {@code
 * application/query/}의 Query Service·Query 인터페이스와 {@code interfaces/}의 REST Controller는
 * 항상 전용 Result/DTO 타입을 반환해야 하고, {@code domain/}의 Aggregate/Entity 클래스를 그대로
 * (혹은 {@code List<...>}/{@code ResponseEntity<...>} 같은 제네릭 안에 담아) 반환하면 안
 * 된다(api-response.md).
 *
 * <p>"Aggregate/Entity 클래스" 목록을 하드코딩하지 않고, {@code <bc>/domain/} 아래 {@code public
 * class}로 선언된 파일 중 이름이 {@code Exception}/{@code Service}로 끝나지 않는 것(도메인
 * 예외·Domain Service 제외)을 동적으로 수집한다 — 이 저장소의 실제 사례로는 {@code Account}/{@code
 * Transaction}/{@code Card}/{@code Payment}/{@code Refund}/{@code Credential}이 여기 해당한다.
 * 새 도메인이 추가돼도 하드코딩된 이름을 갱신할 필요가 없다.
 *
 * <p>반환 타입 매칭은 식별자 경계 검사로 한다 — {@code GetAccountResult}처럼 Aggregate 이름을
 * 부분 문자열로 포함하는 정당한 DTO 이름은 오탐하지 않고, {@code List<Account>}처럼 제네릭 인자로
 * 감싸 반환하는 경우는 잡는다.
 *
 * <p>스캔 대상은 {@code public} 메서드 선언({@code public TYPE name(...)} 형태)으로 한정한다 —
 * Query 인터페이스가 (인터페이스 메서드 관례상) {@code public}을 생략한 경우는 검사하지 않는다;
 * 이 저장소의 Query 인터페이스는 모두 {@code *WithCount} 타입을 반환해 위반 사례가 없고, "public"
 * 요구 없이 넓게 매칭하면 {@code new AccountException(...)} 같은 생성자 호출을 오탐할 위험이
 * 커서 좁은 블록리스트 방식을 택했다.
 */
public final class QueryHandlerNoRawAggregate {
    private QueryHandlerNoRawAggregate() {
    }

    private static final Pattern DOMAIN_CLASS_DECL = Pattern.compile("\\bpublic\\s+(?:final\\s+|abstract\\s+)?class\\s+(\\w+)");
    private static final Pattern METHOD_DECL =
        Pattern.compile("public\\s+(?:static\\s+)?([A-Za-z_][\\w.]*(?:<[^(){};]*>)?(?:\\[\\])?)\\s+(\\w+)\\s*\\(");
    private static final Set<String> TYPE_KEYWORDS = Set.of("class", "record", "interface", "enum", "void");

    public static RuleResult check(String rootPath) {
        File root = new File(rootPath);
        RuleResult result = new RuleResult("query-handler-no-raw-aggregate");

        Set<String> aggregateNames = collectAggregateNames(root);
        if (aggregateNames.isEmpty()) {
            result.add(Finding.skip("<bc>/domain/ 아래 Aggregate로 볼 수 있는 public class 없음"));
            return result;
        }

        boolean found = false;
        for (File f : collectJavaFiles(root)) {
            boolean isQueryFile = pathContains(f, "/application/query/");
            boolean isController = pathContains(f, "/interfaces/") && f.getName().endsWith("Controller.java");
            if (!isQueryFile && !isController) continue;

            String code = stripComments(readText(f));
            String flattened = code.replaceAll("\\s+", " ");
            Matcher m = METHOD_DECL.matcher(flattened);

            boolean fileScanned = false;
            boolean fileHasViolation = false;
            String rel = relTo(f, root);

            while (m.find()) {
                String returnType = m.group(1);
                if (TYPE_KEYWORDS.contains(returnType)) continue; // "public record X(" / "public class X" 오검출 제외
                String methodName = m.group(2);
                fileScanned = true;

                String violatingAggregate = firstReferencedAggregate(returnType, aggregateNames);
                if (violatingAggregate != null) {
                    fileHasViolation = true;
                    result.add(Finding.fail(rel + "#" + methodName,
                        "반환 타입 '" + returnType + "'이 raw Aggregate/Entity '" + violatingAggregate
                            + "'를 직접 노출 — 전용 Result/DTO 타입을 반환해야 함(api-response.md)"));
                }
            }

            if (fileScanned) {
                found = true;
                if (!fileHasViolation) {
                    result.add(Finding.pass(rel + " (raw Aggregate 미노출 확인)"));
                }
            }
        }

        if (!found) {
            result.add(Finding.skip("application/query/ 또는 interfaces/*Controller.java에 public 메서드 없음"));
        }
        return result;
    }

    private static Set<String> collectAggregateNames(File root) {
        Set<String> names = new LinkedHashSet<>();
        for (File f : collectJavaFiles(root)) {
            if (!pathContains(f, "/domain/")) continue;
            String code = stripComments(readText(f));
            Matcher m = DOMAIN_CLASS_DECL.matcher(code);
            if (!m.find()) continue;
            String className = m.group(1);
            if (className.endsWith("Exception") || className.endsWith("Service")) continue;
            names.add(className);
        }
        return names;
    }

    private static String firstReferencedAggregate(String returnType, Set<String> aggregateNames) {
        for (String name : aggregateNames) {
            if (referencesType(returnType, name)) return name;
        }
        return null;
    }

    private static boolean referencesType(String code, String typeName) {
        int idx = 0;
        while ((idx = code.indexOf(typeName, idx)) != -1) {
            boolean leftBoundary = idx == 0 || !isIdentifierChar(code.charAt(idx - 1));
            int end = idx + typeName.length();
            boolean rightBoundary = end >= code.length() || !isIdentifierChar(code.charAt(end));
            if (leftBoundary && rightBoundary) return true;
            idx = end;
        }
        return false;
    }

    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static String stripComments(String content) {
        return content.replaceAll("/\\*[\\s\\S]*?\\*/", "").replaceAll("//.*", "");
    }
}

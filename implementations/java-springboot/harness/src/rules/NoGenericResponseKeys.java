package harness.rules;

import harness.Finding;
import harness.RuleResult;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static harness.JavaFiles.collectJavaFiles;
import static harness.JavaFiles.nameWithoutExtension;
import static harness.JavaFiles.readText;
import static harness.JavaFiles.relTo;

/**
 * [29] 목록 응답 범용 키 금지 — 목록 조회 응답 DTO({@code *Result}/{@code *Response}/{@code
 * *WithCount} 네이밍 관례를 따르는 record)의 최상위 컴포넌트 중 {@code List<...>} 타입 필드명이
 * {@code result}/{@code data}/{@code items} 같은 범용 키면 실패 — 도메인 객체명 복수형(예: {@code
 * transactions}, {@code payments}, {@code accounts})을 써야 한다(api-response.md).
 *
 * <p>루트 문서는 단건 응답 안에 중첩된 하위 컬렉션(예: 주문 자신의 line item 목록을 담는 {@code
 * items} 필드)은 도메인 개념(주문의 품목)이라 허용된다고 명시한다 — 금지 대상은 "목록 조회
 * 응답"의 최상위(outer) 페이지네이션 배열 필드뿐이다. 그래서 이 규칙은 파일명과 동일한 최상위
 * record 선언의 컴포넌트만 파싱하고, 그 안에 중첩된 내부 record(예: {@code GetTransactionsResult}
 * 안의 {@code TransactionSummary})는 검사하지 않는다.
 */
public final class NoGenericResponseKeys {
    private NoGenericResponseKeys() {
    }

    private static final Set<String> FORBIDDEN_KEYS = Set.of("result", "data", "items");
    private static final Pattern RECORD_DECL_PREFIX = Pattern.compile("\\brecord\\s+(\\w+)\\s*\\(");

    public static RuleResult check(String rootPath) {
        File root = new File(rootPath);
        RuleResult result = new RuleResult("no-generic-response-keys");
        boolean found = false;

        for (File f : collectJavaFiles(root)) {
            String typeName = nameWithoutExtension(f);
            if (!(typeName.endsWith("Result") || typeName.endsWith("Response") || typeName.endsWith("WithCount"))) {
                continue;
            }

            String code = stripComments(readText(f));
            Map<String, String> fields = outerRecordComponents(code, typeName);
            if (fields == null) continue; // record가 아니거나(예: 인터페이스) 최상위 타입명이 다름

            found = true;
            String rel = relTo(f, root);

            List<String> violations = new ArrayList<>();
            for (Map.Entry<String, String> e : fields.entrySet()) {
                if (FORBIDDEN_KEYS.contains(e.getKey()) && isListType(e.getValue())) {
                    violations.add(e.getKey());
                }
            }

            if (violations.isEmpty()) {
                result.add(Finding.pass(rel + " (" + typeName + " — 목록 필드에 범용 키 미사용 확인)"));
            } else {
                result.add(Finding.fail(rel,
                    "'" + typeName + "'의 목록 필드명이 범용 키(" + violations
                        + ") — result/data/items 대신 도메인 객체명 복수형을 써야 함(api-response.md)"));
            }
        }

        if (!found) {
            result.add(Finding.skip("*Result.java/*Response.java/*WithCount.java record 없음"));
        }
        return result;
    }

    /** 파일명과 이름이 같은 최상위 record 선언의 컴포넌트만 이름 -> 타입 맵으로 반환한다(중첩 record 제외). */
    private static Map<String, String> outerRecordComponents(String code, String typeName) {
        Matcher recordMatcher = RECORD_DECL_PREFIX.matcher(code);
        while (recordMatcher.find()) {
            if (!recordMatcher.group(1).equals(typeName)) continue;
            int paramsStart = recordMatcher.end();
            int depth = 1;
            int i = paramsStart;
            for (; i < code.length() && depth > 0; i++) {
                char c = code.charAt(i);
                if (c == '(') depth++;
                else if (c == ')') depth--;
            }
            return parseParamList(code.substring(paramsStart, i - 1));
        }
        return null;
    }

    private static Map<String, String> parseParamList(String params) {
        Map<String, String> fields = new LinkedHashMap<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i <= params.length(); i++) {
            char c = i < params.length() ? params.charAt(i) : ',';
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (c == ',' && depth == 0) {
                String segment = params.substring(start, i).trim();
                start = i + 1;
                if (segment.isEmpty()) continue;
                int lastSpace = segment.lastIndexOf(' ');
                if (lastSpace < 0) continue;
                String type = segment.substring(0, lastSpace).trim();
                String name = segment.substring(lastSpace + 1).trim();
                fields.put(name, type);
            }
        }
        return fields;
    }

    private static boolean isListType(String type) {
        if (type == null) return false;
        String t = type.trim();
        return t.startsWith("List<") || t.startsWith("java.util.List<") || t.endsWith("[]");
    }

    private static String stripComments(String content) {
        return content.replaceAll("/\\*[\\s\\S]*?\\*/", "").replaceAll("//.*", "");
    }
}

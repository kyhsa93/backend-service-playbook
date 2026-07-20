package harness.rules;

import harness.Finding;
import harness.RuleResult;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static harness.JavaFiles.collectJavaFiles;
import static harness.JavaFiles.nameWithoutExtension;
import static harness.JavaFiles.readText;
import static harness.JavaFiles.relTo;

/**
 * [25] 에러 응답 4필드 스키마 — {@code common/web/GlobalExceptionHandler}(전역 {@code
 * @RestControllerAdvice})가 {@code @ExceptionHandler} 메서드에서 반환하는 응답 DTO가 정확히
 * {@code statusCode}(숫자)/{@code code}(문자열)/{@code message}(문자열 또는 배열)/{@code error}(문자열)
 * 4필드만 가져야 한다(error-handling.md). 더 많거나 적은 필드, 다른 이름의 필드가 있으면 실패.
 *
 * <p>DTO 타입명을 하드코딩(예: "ErrorResponse")하지 않고, GlobalExceptionHandler의 {@code
 * @ExceptionHandler} 메서드가 실제로 반환하는 {@code ResponseEntity<Xxx>}의 제네릭 타입을 파싱해
 * 동적으로 찾는다 — 도메인 이름이 바뀌거나 응답 DTO 이름이 바뀌어도 규칙이 깨지지 않는다. 찾은
 * 타입은 {@code record}(주 사용 형태) 또는 일반 {@code class}(필드 선언) 둘 다 인식한다.
 */
public final class ErrorResponseSchema {
    private ErrorResponseSchema() {
    }

    private static final Pattern HANDLER_RETURN_TYPE =
        Pattern.compile("@ExceptionHandler[\\s\\S]{0,400}?ResponseEntity\\s*<\\s*(\\w+)\\s*>");
    private static final Pattern RECORD_DECL_PREFIX = Pattern.compile("\\brecord\\s+(\\w+)\\s*\\(");
    private static final Pattern FIELD_DECL =
        Pattern.compile("(?m)^\\s*(?:@\\w+(?:\\([^)]*\\))?\\s*)*private\\s+([\\w<>\\[\\],.\\s]+?)\\s+(\\w+)\\s*;");

    private static final Set<String> REQUIRED_FIELDS =
        Set.of("statusCode", "code", "message", "error");

    public static RuleResult check(String rootPath) {
        File root = new File(rootPath);
        RuleResult result = new RuleResult("error-response-schema");

        File handlerFile = null;
        for (File f : collectJavaFiles(root)) {
            if (nameWithoutExtension(f).equals("GlobalExceptionHandler")) {
                handlerFile = f;
                break;
            }
        }

        if (handlerFile == null) {
            result.add(Finding.skip("common/web/GlobalExceptionHandler.java(전역 @RestControllerAdvice) 없음"));
            return result;
        }

        String handlerCode = stripComments(readText(handlerFile));
        String handlerRel = relTo(handlerFile, root);

        Set<String> responseTypes = new LinkedHashSet<>();
        Matcher m = HANDLER_RETURN_TYPE.matcher(handlerCode);
        while (m.find()) {
            responseTypes.add(m.group(1));
        }

        if (responseTypes.isEmpty()) {
            result.add(Finding.fail(handlerRel,
                "@ExceptionHandler 메서드가 ResponseEntity<Xxx>를 반환하는 형태를 찾을 수 없음 — 4필드 응답 스키마를 확인할 수 없음(error-handling.md)"));
            return result;
        }

        for (String typeName : responseTypes) {
            File dtoFile = null;
            for (File f : collectJavaFiles(root)) {
                if (nameWithoutExtension(f).equals(typeName)) {
                    dtoFile = f;
                    break;
                }
            }

            if (dtoFile == null) {
                result.add(Finding.fail(handlerRel + " -> " + typeName,
                    "GlobalExceptionHandler가 반환하는 응답 타입 '" + typeName + "'의 선언을 프로젝트 안에서 찾을 수 없음 — 표준 4필드 ErrorResponse가 아닐 수 있음(error-handling.md)"));
                continue;
            }

            String dtoRel = relTo(dtoFile, root);
            String dtoCode = stripComments(readText(dtoFile));
            Map<String, String> fields = extractFields(dtoCode, typeName);

            if (fields == null) {
                result.add(Finding.fail(dtoRel,
                    "'" + typeName + "'의 필드 목록을 파싱할 수 없음(record 컴포넌트 또는 private 필드 선언 형태가 아님)"));
                continue;
            }

            classify(result, dtoRel, typeName, fields);
        }

        return result;
    }

    private static void classify(RuleResult result, String dtoRel, String typeName, Map<String, String> fields) {
        Set<String> actualNames = fields.keySet();

        if (!actualNames.equals(REQUIRED_FIELDS)) {
            Set<String> missing = new LinkedHashSet<>(REQUIRED_FIELDS);
            missing.removeAll(actualNames);
            Set<String> extra = new LinkedHashSet<>(actualNames);
            extra.removeAll(REQUIRED_FIELDS);
            StringBuilder reason = new StringBuilder(
                "'" + typeName + "'는 정확히 statusCode/code/message/error 4필드만 가져야 함(error-handling.md).");
            if (!missing.isEmpty()) reason.append(" 누락: ").append(missing).append(".");
            if (!extra.isEmpty()) reason.append(" 불필요/오타 필드: ").append(extra).append(".");
            result.add(Finding.fail(dtoRel, reason.toString()));
            return;
        }

        boolean statusCodeOk = isNumericType(fields.get("statusCode"));
        boolean codeOk = isStringType(fields.get("code"));
        boolean messageOk = isStringType(fields.get("message")) || isStringArrayType(fields.get("message"));
        boolean errorOk = isStringType(fields.get("error"));

        if (statusCodeOk && codeOk && messageOk && errorOk) {
            result.add(Finding.pass(dtoRel + " (" + typeName + " — statusCode/code/message/error 4필드 스키마 확인)"));
        } else {
            StringBuilder reason = new StringBuilder("'" + typeName + "' 필드 타입이 예상과 다름:");
            if (!statusCodeOk) reason.append(" statusCode는 숫자 타입이어야 함(실제: ").append(fields.get("statusCode")).append(").");
            if (!codeOk) reason.append(" code는 String이어야 함(실제: ").append(fields.get("code")).append(").");
            if (!messageOk) reason.append(" message는 String 또는 배열/List<String>이어야 함(실제: ").append(fields.get("message")).append(").");
            if (!errorOk) reason.append(" error는 String이어야 함(실제: ").append(fields.get("error")).append(").");
            result.add(Finding.fail(dtoRel, reason.toString()));
        }
    }

    /** record 컴포넌트 목록 또는 class의 private 필드 선언을 파싱해 이름 -> 타입 맵으로 반환한다. */
    private static Map<String, String> extractFields(String code, String typeName) {
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
            String params = code.substring(paramsStart, i - 1);
            return parseParamList(params);
        }

        // record가 아니면 일반 class의 private 필드 선언을 찾는다
        Matcher fieldMatcher = FIELD_DECL.matcher(code);
        Map<String, String> fields = new LinkedHashMap<>();
        while (fieldMatcher.find()) {
            fields.put(fieldMatcher.group(2), fieldMatcher.group(1).trim());
        }
        return fields.isEmpty() ? null : fields;
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

    private static boolean isNumericType(String type) {
        if (type == null) return false;
        String t = type.trim();
        return t.equals("int") || t.equals("Integer") || t.equals("long") || t.equals("Long")
            || t.equals("short") || t.equals("Short");
    }

    private static boolean isStringType(String type) {
        return type != null && type.trim().equals("String");
    }

    private static boolean isStringArrayType(String type) {
        if (type == null) return false;
        String t = type.trim().replaceAll("\\s+", "");
        return t.equals("String[]") || t.equals("List<String>") || t.equals("List<String>...");
    }

    private static String stripComments(String content) {
        return content.replaceAll("/\\*[\\s\\S]*?\\*/", "").replaceAll("//.*", "");
    }
}

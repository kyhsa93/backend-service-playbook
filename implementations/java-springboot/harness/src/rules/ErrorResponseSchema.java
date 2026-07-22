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
 * [25] The 4-field error-response schema — the response DTO that {@code
 * common/web/GlobalExceptionHandler} (a global {@code @RestControllerAdvice}) returns
 * from an {@code @ExceptionHandler} method must have exactly these 4 fields: {@code
 * statusCode} (number)/{@code code} (string)/{@code message} (string or array)/{@code
 * error} (string) (error-handling.md). Fails if there are more or fewer fields, or a
 * differently-named field.
 *
 * <p>Rather than hardcoding the DTO type name (e.g. "ErrorResponse"), it dynamically finds
 * the generic type of the {@code ResponseEntity<Xxx>} that GlobalExceptionHandler's {@code
 * @ExceptionHandler} methods actually return, by parsing it — the rule doesn't break even
 * if the domain name or the response DTO's name changes. The type it finds is recognized
 * whether it's a {@code record} (the primary usage) or a plain {@code class} (field
 * declarations).
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
            result.add(Finding.skip("No common/web/GlobalExceptionHandler.java (global @RestControllerAdvice)"));
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
                "Could not find an @ExceptionHandler method that returns ResponseEntity<Xxx> — could not confirm the 4-field response schema (error-handling.md)"));
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
                    "Could not find the declaration of the response type '" + typeName + "' that GlobalExceptionHandler returns, anywhere in the project — it may not be a standard 4-field ErrorResponse (error-handling.md)"));
                continue;
            }

            String dtoRel = relTo(dtoFile, root);
            String dtoCode = stripComments(readText(dtoFile));
            Map<String, String> fields = extractFields(dtoCode, typeName);

            if (fields == null) {
                result.add(Finding.fail(dtoRel,
                    "Could not parse the field list of '" + typeName + "' (not in the form of record components or private field declarations)"));
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
                "'" + typeName + "' must have exactly these 4 fields: statusCode/code/message/error (error-handling.md).");
            if (!missing.isEmpty()) reason.append(" Missing: ").append(missing).append(".");
            if (!extra.isEmpty()) reason.append(" Extra/misnamed fields: ").append(extra).append(".");
            result.add(Finding.fail(dtoRel, reason.toString()));
            return;
        }

        boolean statusCodeOk = isNumericType(fields.get("statusCode"));
        boolean codeOk = isStringType(fields.get("code"));
        boolean messageOk = isStringType(fields.get("message")) || isStringArrayType(fields.get("message"));
        boolean errorOk = isStringType(fields.get("error"));

        if (statusCodeOk && codeOk && messageOk && errorOk) {
            result.add(Finding.pass(dtoRel + " (" + typeName + " — confirmed the statusCode/code/message/error 4-field schema)"));
        } else {
            StringBuilder reason = new StringBuilder("'" + typeName + "' field types don't match what's expected:");
            if (!statusCodeOk) reason.append(" statusCode must be a numeric type (actual: ").append(fields.get("statusCode")).append(").");
            if (!codeOk) reason.append(" code must be String (actual: ").append(fields.get("code")).append(").");
            if (!messageOk) reason.append(" message must be String or an array/List<String> (actual: ").append(fields.get("message")).append(").");
            if (!errorOk) reason.append(" error must be String (actual: ").append(fields.get("error")).append(").");
            result.add(Finding.fail(dtoRel, reason.toString()));
        }
    }

    /** Parses either the record component list or a class's private field declarations, returning a name -> type map. */
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

        // If it's not a record, look for a plain class's private field declarations
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

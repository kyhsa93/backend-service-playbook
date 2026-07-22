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
 * [29] Generic keys are forbidden on list responses — fails if a list-query response DTO
 * (a record following the {@code *Result}/{@code *Response}/{@code *WithCount} naming
 * convention) has a top-level {@code List<...>}-typed component named with a generic key
 * like {@code result}/{@code data}/{@code items} — it must instead use the plural of the
 * domain object's name (e.g. {@code transactions}, {@code payments}, {@code accounts})
 * (api-response.md).
 *
 * <p>The root docs state that a nested sub-collection inside a single-record response
 * (e.g. an {@code items} field holding an order's own line items) is allowed because it's
 * a domain concept (the order's line items) — what's forbidden is only the top-level
 * (outer) pagination array field of a "list-query response." So this rule only parses the
 * components of the top-level record declaration matching the file name, and does not
 * check a nested inner record within it (e.g. {@code TransactionSummary} inside {@code
 * GetTransactionsResult}).
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
            if (fields == null) continue; // not a record (e.g. an interface), or the top-level type name differs

            found = true;
            String rel = relTo(f, root);

            List<String> violations = new ArrayList<>();
            for (Map.Entry<String, String> e : fields.entrySet()) {
                if (FORBIDDEN_KEYS.contains(e.getKey()) && isListType(e.getValue())) {
                    violations.add(e.getKey());
                }
            }

            if (violations.isEmpty()) {
                result.add(Finding.pass(rel + " (" + typeName + " — confirmed no generic keys on list fields)"));
            } else {
                result.add(Finding.fail(rel,
                    "'" + typeName + "'s list field name is a generic key (" + violations
                        + ") — must use the plural of the domain object's name instead of result/data/items (api-response.md)"));
            }
        }

        if (!found) {
            result.add(Finding.skip("No *Result.java/*Response.java/*WithCount.java record"));
        }
        return result;
    }

    /** Returns only the components of the top-level record declaration whose name matches the file name, as a name -> type map (excludes nested records). */
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

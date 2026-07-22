package harness.rules;

import harness.Finding;
import harness.RuleResult;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static harness.JavaFiles.collectJavaFiles;
import static harness.JavaFiles.relTo;
import static harness.JavaFiles.readText;

/**
 * [32] Machine-readable API documentation (OpenAPI) — every operation on a {@code @RestController}
 * must have a springdoc {@code @Operation} with a non-blank {@code summary} and {@code
 * description}, and every operation must have at least one documented non-2xx {@code
 * @ApiResponse} (class-level {@code @ApiResponse}s — e.g. a controller-wide 401 — count toward
 * this too), not just the success response (docs/architecture/api-response.md "Machine-readable
 * API documentation (OpenAPI)").
 *
 * <p>This is a regex/brace-depth heuristic, not a full Java parser (matching this harness's other
 * rules) — it locates each HTTP-mapping annotation ({@code @GetMapping}/{@code @PostMapping}/etc.),
 * takes the text between it and the following method signature as that operation's own annotation
 * block, and combines it with the class-level annotation block (the text before the {@code class}
 * keyword) to decide pass/fail.
 */
public final class ApiDocumentation {
    private ApiDocumentation() {
    }

    private static final Pattern REST_CONTROLLER = Pattern.compile("@RestController(?!Advice)");
    private static final Pattern CLASS_DECL = Pattern.compile("(?m)^\\s*(?:public\\s+)?(?:final\\s+)?class\\s+\\w+");
    private static final Pattern MAPPING_ANNOTATION =
        Pattern.compile("@(?:Get|Post|Put|Patch|Delete|Request)Mapping\\b");
    private static final Pattern METHOD_SIGNATURE =
        Pattern.compile("(?s)\\bpublic\\s+[\\w<>\\[\\],.\\s]+?\\s+(\\w+)\\s*\\(");
    private static final Pattern RESPONSE_CODE = Pattern.compile("responseCode\\s*=\\s*\"(\\d+)\"");
    private static final Pattern SUMMARY_ATTR = Pattern.compile("\\bsummary\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern DESCRIPTION_ATTR = Pattern.compile("\\bdescription\\s*=\\s*\"([^\"]*)\"");

    public static RuleResult check(String rootPath) {
        File root = new File(rootPath);
        RuleResult result = new RuleResult("api-documentation");
        boolean foundController = false;

        for (File f : collectJavaFiles(root)) {
            String rawContent = readText(f);
            if (!REST_CONTROLLER.matcher(rawContent).find()) continue;
            foundController = true;
            String rel = relTo(f, root);
            String content = stripComments(rawContent);

            Matcher classMatcher = CLASS_DECL.matcher(content);
            boolean hasClassDecl = classMatcher.find();
            String classAnnotations = hasClassDecl ? content.substring(0, classMatcher.start()) : content;
            int classBodyStart = hasClassDecl ? classMatcher.end() : 0;

            // Scanning only starts after the class declaration so a class-level @RequestMapping
            // (the base path, e.g. @RequestMapping("/accounts")) isn't mistaken for a method's own
            // HTTP-mapping annotation.
            List<int[]> mappingSpans = new ArrayList<>();
            Matcher mappingMatcher = MAPPING_ANNOTATION.matcher(content);
            mappingMatcher.region(classBodyStart, content.length());
            while (mappingMatcher.find()) {
                mappingSpans.add(new int[] {mappingMatcher.start(), mappingMatcher.end()});
            }

            if (mappingSpans.isEmpty()) {
                result.add(Finding.skip(rel + " (no HTTP-mapped method found)"));
                continue;
            }

            for (int[] span : mappingSpans) {
                String operationBlock = extractOperationBlock(content, span[1]);
                Matcher sigMatcher = METHOD_SIGNATURE.matcher(content);
                String methodName = sigMatcher.find(span[1]) ? sigMatcher.group(1) : "<unknown method>";
                String combined = classAnnotations + "\n" + operationBlock;
                String label = rel + " -> " + methodName + "()";

                if (!hasNonBlankOperationDoc(operationBlock)) {
                    result.add(Finding.fail(label,
                        "Missing @Operation summary/description — every REST operation needs both (api-response.md, checklist.md STEP 6)"));
                    continue;
                }

                if (!hasNonSuccessResponse(combined)) {
                    result.add(Finding.fail(label,
                        "No non-2xx @ApiResponse documented — only the success response is described (api-response.md, checklist.md STEP 6)"));
                    continue;
                }

                result.add(Finding.pass(label));
            }
        }

        if (!foundController) result.add(Finding.skip("No @RestController"));
        return result;
    }

    /** The text between a mapping annotation and the next method signature (`public ... name(`). */
    private static String extractOperationBlock(String content, int from) {
        Matcher sigMatcher = METHOD_SIGNATURE.matcher(content);
        if (sigMatcher.find(from)) {
            return content.substring(from, sigMatcher.start());
        }
        return content.substring(from, Math.min(content.length(), from + 2000));
    }

    private static boolean hasNonBlankOperationDoc(String operationBlock) {
        int operationIdx = operationBlock.indexOf("@Operation");
        if (operationIdx < 0) return false;
        String args = extractParenBlock(operationBlock, operationIdx);
        Matcher summaryMatcher = SUMMARY_ATTR.matcher(args);
        Matcher descMatcher = DESCRIPTION_ATTR.matcher(args);
        boolean summaryOk = summaryMatcher.find() && !summaryMatcher.group(1).isBlank();
        boolean descOk = descMatcher.find() && !descMatcher.group(1).isBlank();
        return summaryOk && descOk;
    }

    private static boolean hasNonSuccessResponse(String combinedBlock) {
        int idx = 0;
        while (true) {
            int found = combinedBlock.indexOf("@ApiResponse", idx);
            if (found < 0) break;
            String args = extractParenBlock(combinedBlock, found);
            Matcher codeMatcher = RESPONSE_CODE.matcher(args);
            if (codeMatcher.find() && !codeMatcher.group(1).startsWith("2")) {
                return true;
            }
            idx = found + "@ApiResponse".length();
        }
        return false;
    }

    /** Given the index of an `@AnnotationName` occurrence, returns the text inside its balanced `(...)`. */
    private static String extractParenBlock(String text, int annotationIndex) {
        int parenStart = text.indexOf('(', annotationIndex);
        if (parenStart < 0) return "";
        int depth = 1;
        int i = parenStart + 1;
        for (; i < text.length() && depth > 0; i++) {
            char c = text.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
        }
        return text.substring(parenStart + 1, Math.max(parenStart + 1, i - 1));
    }

    private static String stripComments(String content) {
        return content.replaceAll("/\\*[\\s\\S]*?\\*/", "").replaceAll("//.*", "");
    }
}

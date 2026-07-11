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
 * [11] CQRS Query 순수성 — application/query/ 아래 파일은 쓰기용 Repository 타입을
 * 참조하면 안 된다(cqrs-pattern.md). nestjs harness의 cqrs-pattern evaluator와 동일한
 * 취지를 이 코드베이스의 관용구(javac 기반 정적 검사)로 이식한 규칙이다.
 *
 * "Repository" 문자열 탐지는 주석(Javadoc 등)을 제외한 실제 코드에서만 수행한다 —
 * 예를 들어 {@code AccountQuery}의 Javadoc은 분리 취지를 설명하려고 일부러
 * {@code AccountRepository}를 언급하는데, 이런 문서용 언급까지 위반으로 잡으면 안 된다.
 */
public final class CqrsQueryPurity {
    private CqrsQueryPurity() {
    }

    private static final Pattern BLOCK_COMMENT = Pattern.compile("(?s)/\\*.*?\\*/");
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\n]*");

    public static RuleResult check(String rootPath) {
        File root = new File(rootPath);
        RuleResult result = new RuleResult("cqrs-query-purity");
        boolean found = false;

        for (File f : collectJavaFiles(root)) {
            if (!pathContains(f, "/application/query/")) continue;
            found = true;
            String rel = relTo(f, root);
            String code = stripComments(readText(f));
            if (code.contains("Repository")) {
                result.add(Finding.fail(rel,
                    "application/query/ 하위 파일은 쓰기용 Repository 타입을 참조하면 안 됨 — Query 전용 인터페이스(예: AccountQuery)에 의존해야 함(cqrs-pattern.md)"));
            } else {
                result.add(Finding.pass(rel + " (Repository 미참조 확인)"));
            }
        }

        if (!found) result.add(Finding.skip("application/query/ 없음"));
        return result;
    }

    private static String stripComments(String content) {
        String withoutBlocks = BLOCK_COMMENT.matcher(content).replaceAll("");
        return LINE_COMMENT.matcher(withoutBlocks).replaceAll("");
    }
}

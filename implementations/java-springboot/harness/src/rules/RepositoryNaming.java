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
 * [12] Repository/Query 메서드 네이밍 — domain/ 또는 application/query/ 안의 *Repository/*Query
 * 인터페이스는 find&lt;Noun&gt;s(복수형, 단건 조회도 동일 메서드를 재사용)/save&lt;Noun&gt;/delete&lt;Noun&gt;
 * 형태만 써야 한다(repository-pattern.md). infrastructure/(구현체, 내부 Spring Data JPA 파생 쿼리
 * 메서드)는 검사 대상이 아니다 — 그쪽은 구현 세부사항이라 derived-query 스타일 메서드가 정당하게 존재할 수
 * 있다.
 *
 * <p>블록리스트 방식(좁고 정밀한 매칭)만 쓴다 — 폭넓은 긍정 매칭 문법을 쓰면 {@code
 * hasTransactionWithReference} 같은 정당한 메서드가 오탐될 수 있다. 이 규칙은 5개 언어 구현 전체에서
 * 발견된 실제 회귀(Card의 {@code findByAccountIdAndStatusIn} + bare {@code save} 등)를 다시는 조용히
 * 재발하지 않도록 자동화한다.
 */
public final class RepositoryNaming {
    private RepositoryNaming() {
    }

    private static final Pattern INTERFACE_DECL = Pattern.compile("\\binterface\\s+(\\w+)");
    private static final Pattern METHOD_DECL = Pattern.compile("(\\w+)\\s*\\([^)]*\\)\\s*;");

    // find 뒤 어딘가에 By/by가 나오고 그 직후가 대문자로 이어지는 형태만 잡는다 — findByAccountId,
    // findAccountsByOwnerId 둘 다 catch하되 "findAccountsBypassCache" 같은 우연한 부분 문자열은
    // 대문자 경계가 없어서 피해간다.
    private static final Pattern FIND_BY = Pattern.compile("^[Ff]ind\\w*[Bb]y[A-Z]");

    public static RuleResult check(String rootPath) {
        File root = new File(rootPath);
        RuleResult result = new RuleResult("repository-naming");
        boolean found = false;

        for (File f : collectJavaFiles(root)) {
            if (!pathContains(f, "/domain/") && !pathContains(f, "/application/query/")) continue;

            String code = stripComments(readText(f));
            Matcher ifaceMatcher = INTERFACE_DECL.matcher(code);
            if (!ifaceMatcher.find()) continue; // record/class/enum(예: AccountFindQuery) — 검사 대상 아님

            String interfaceName = ifaceMatcher.group(1);
            if (!interfaceName.endsWith("Repository") && !interfaceName.endsWith("Query")) continue;

            found = true;
            String rel = relTo(f, root);
            String flattened = code.replaceAll("\\s+", " ");
            Matcher methodMatcher = METHOD_DECL.matcher(flattened);

            boolean fileHasViolation = false;
            while (methodMatcher.find()) {
                String methodName = methodMatcher.group(1);
                String violation = classify(methodName);
                if (violation != null) {
                    fileHasViolation = true;
                    result.add(Finding.fail(rel + "#" + methodName,
                        violation + " — Repository/Query 인터페이스 메서드는 find<Noun>s(복수형, 단건 조회도 재사용)/save<Noun>/delete<Noun> 형태여야 함(repository-pattern.md)"));
                }
            }
            if (!fileHasViolation) {
                result.add(Finding.pass(rel + " (" + interfaceName + " 네이밍 규칙 준수 확인)"));
            }
        }

        if (!found) {
            result.add(Finding.skip("domain/ 또는 application/query/ 안에 *Repository/*Query 인터페이스 없음"));
        }
        return result;
    }

    private static String classify(String methodName) {
        if (FIND_BY.matcher(methodName).find()) {
            return "'" + methodName + "'는 findBy... 파생 쿼리 스타일(Spring Data 관용구)을 흉내낸 메서드";
        }
        if (methodName.equals("findAll")) {
            return "'" + methodName + "'는 대상 명사 없는 bare findAll";
        }
        if (methodName.startsWith("count")) {
            return "'" + methodName + "'는 count 전용 메서드 — 개수는 별도 메서드가 아니라 find 결과에 *WithCount로 함께 반환해야 함";
        }
        if (methodName.equals("save")) {
            return "'" + methodName + "'는 대상 명사 없는 bare save";
        }
        if (methodName.equals("delete")) {
            return "'" + methodName + "'는 대상 명사 없는 bare delete";
        }
        return null;
    }

    private static String stripComments(String content) {
        return content.replaceAll("/\\*[\\s\\S]*?\\*/", "").replaceAll("//.*", "");
    }
}

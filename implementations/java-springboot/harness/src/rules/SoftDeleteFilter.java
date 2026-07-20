package harness.rules;

import harness.Finding;
import harness.RuleResult;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static harness.JavaFiles.collectJavaFiles;
import static harness.JavaFiles.nameWithoutExtension;
import static harness.JavaFiles.readText;
import static harness.JavaFiles.relTo;

/**
 * [26] Soft Delete 필터 — {@code deletedAt} 컬럼을 가진 JPA Entity는 hard delete가 금지되고, 조회
 * 시 항상 {@code deletedAt IS NULL}(또는 동일한 의미의 필터)이 적용되어야 한다(persistence.md —
 * "삭제는 기본적으로 soft delete, 조회 시 deletedAt IS NULL이 기본 적용").
 *
 * <p>이 저장소가 실제로 쓰는 메커니즘을 먼저 확인했다: {@code AccountJpaEntity}에 전역 Hibernate
 * {@code @SQLRestriction}/{@code @Where} 애노테이션을 붙이는 방식이 아니라, {@code
 * AccountRepositoryImpl}이 수동 조립하는 JPQL({@code buildJpql()})에 매번 {@code "a.deletedAt IS
 * NULL"} 조건을 포함시키는 방식이다. 두 메커니즘 중 어느 쪽을 쓰든 통과하도록 둘 다 인식한다 —
 * Entity 클래스에 {@code @SQLRestriction}/{@code @Where}가 있으면 전역 필터로 간주해 해당
 * RepositoryImpl 검사를 건너뛰고, 없으면 조회(find) 메서드를 가진 *RepositoryImpl.java 파일
 * 전체에서 deletedAt 필터 텍스트를 찾는다(메서드 단위가 아니라 파일 단위 검사 — buildJpql처럼
 * find 메서드가 별도 private 헬퍼로 JPQL을 위임하는 구조가 흔해 메서드 본문만 보면 오탐하기
 * 쉽다).
 *
 * <p>{@code deletedAt} 컬럼이 아예 없는 Entity(Card/Payment/Refund/Credential/Transaction 등 —
 * 아직 삭제 유스케이스가 없는 Aggregate)는 soft delete 요구사항 자체가 적용되지 않으므로 검사
 * 대상에서 자연히 제외된다(persistence.md도 Transaction에 대해 이를 명시적으로 허용한다).
 */
public final class SoftDeleteFilter {
    private SoftDeleteFilter() {
    }

    private static final Pattern DELETED_AT_FIELD =
        Pattern.compile("(?m)^\\s*(?:@\\w+(?:\\([^)]*\\))?\\s*)*private\\s+\\S+\\s+deletedAt\\s*;");
    private static final Pattern GLOBAL_FILTER_ANNOTATION = Pattern.compile("@(SQLRestriction|Where)\\s*\\(");
    private static final Pattern FIND_METHOD_DECL = Pattern.compile("(?<![.\\w])\\bfind\\w*\\s*\\([^)]*\\)\\s*\\{");

    public static RuleResult check(String rootPath) {
        File root = new File(rootPath);
        RuleResult result = new RuleResult("soft-delete-filter");

        List<File> allFiles = collectJavaFiles(root);
        List<File> entitiesWithDeletedAt = new ArrayList<>();
        for (File f : allFiles) {
            if (!nameWithoutExtension(f).endsWith("JpaEntity")) continue;
            if (DELETED_AT_FIELD.matcher(stripComments(readText(f))).find()) {
                entitiesWithDeletedAt.add(f);
            }
        }

        if (entitiesWithDeletedAt.isEmpty()) {
            result.add(Finding.skip("deletedAt 컬럼을 가진 JPA Entity 없음"));
            return result;
        }

        boolean anyChecked = false;
        for (File entityFile : entitiesWithDeletedAt) {
            String entityName = nameWithoutExtension(entityFile);
            String entityCode = stripComments(readText(entityFile));
            String entityRel = relTo(entityFile, root);

            if (GLOBAL_FILTER_ANNOTATION.matcher(entityCode).find()) {
                anyChecked = true;
                result.add(Finding.pass(entityRel + " (@SQLRestriction/@Where 전역 soft-delete 필터 확인)"));
                continue;
            }

            boolean referenced = false;
            for (File f : allFiles) {
                if (!nameWithoutExtension(f).endsWith("RepositoryImpl")) continue;
                String code = stripComments(readText(f));
                if (!code.contains(entityName)) continue;
                if (!FIND_METHOD_DECL.matcher(code).find()) continue; // find 메서드가 없으면(쓰기 전용) 검사 대상 아님
                referenced = true;
                anyChecked = true;

                String rel = relTo(f, root);
                if (containsDeletedAtFilter(code)) {
                    result.add(Finding.pass(rel + " (" + entityName + " 조회 시 deletedAt 필터 확인)"));
                } else {
                    result.add(Finding.fail(rel,
                        entityName + "를 조회하는 find 메서드가 있지만 deletedAt IS NULL(또는 동일 의미의 필터)이 없음 — soft delete된 행이 그대로 노출될 수 있음(persistence.md)"));
                }
            }

            if (!referenced) {
                result.add(Finding.skip(entityRel + " — 조회(find) 메서드를 가진 *RepositoryImpl.java 없음"));
            }
        }

        if (!anyChecked) {
            result.add(Finding.skip("deletedAt 컬럼을 가진 Entity를 조회하는 *RepositoryImpl.java 없음"));
        }
        return result;
    }

    private static boolean containsDeletedAtFilter(String code) {
        String lower = code.toLowerCase();
        return lower.contains("deletedat") || lower.contains("deleted_at");
    }

    private static String stripComments(String content) {
        return content.replaceAll("/\\*[\\s\\S]*?\\*/", "").replaceAll("//.*", "");
    }
}

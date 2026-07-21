package harness.rules;

import harness.Finding;
import harness.RuleResult;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static harness.JavaFiles.readText;
import static harness.JavaFiles.relTo;

/**
 * [32] 운영(prod) 설정에 ORM 자동 스키마 동기화 금지 — {@code spring.jpa.hibernate.ddl-auto}가
 * {@code update}/{@code create}/{@code create-drop}이면 실패. 스키마 변경은 Flyway/Liquibase
 * 마이그레이션으로만 관리해야 한다(persistence.md) — 자동 동기화는 개발/테스트 환경 전용
 * 허용(persistence.md "테스트 환경은 예외").
 *
 * <p>두 파일을 각각 확인한다:
 * <ul>
 *   <li>기본(프로필 없음) {@code application.yml}/{@code application.yaml} — 프로덕션에서
 *   {@code SPRING_PROFILES_ACTIVE}가 누락되면 이 값이 그대로 적용되므로, 여기가 이미
 *   {@code update} 등으로 설정돼 있으면 위험하다.</li>
 *   <li>{@code application-prod.yml}/{@code application-prod.yaml}(또는 {@code -production}
 *   변형) — prod 프로필 전용 override. {@code ddl-auto} 키 자체가 없으면 기본 파일 값을 그대로
 *   물려받으므로(이미 위에서 별도 검사) PASS로 본다.</li>
 * </ul>
 *
 * <p>{@code src/test/resources}는 검사 대상에서 제외한다 — {@code
 * @DynamicPropertySource}로 Testcontainers 테스트가 {@code create-drop}을 쓰는 것은
 * persistence.md가 명시한 정당한 예외이고, 애초에 Java 코드(어노테이션)이지 YAML이 아니라서 이
 * 규칙의 파싱 대상도 아니다.
 */
public final class NoOrmAutoSyncInProdConfig {
    private NoOrmAutoSyncInProdConfig() {
    }

    // build/는 Gradle이 src/main/resources를 그대로 복사해 넣는 산출물 디렉토리라, 제외하지
    // 않으면 같은 파일이 build/resources에도 잡혀 중복 스캔되고, 빌드 시점에 따라 stale한
    // 사본을 검사 대상으로 고를 위험도 있다(JavaFiles.java와 동일한 이유로 제외).
    private static final Set<String> EXCLUDED_DIRS = Set.of("test", ".git", "build");
    private static final Set<String> DEFAULT_NAMES = Set.of("application.yml", "application.yaml");
    private static final Set<String> PROD_NAMES = Set.of(
        "application-prod.yml", "application-prod.yaml",
        "application-production.yml", "application-production.yaml");
    private static final Set<String> DISALLOWED_VALUES = Set.of("update", "create", "create-drop");
    private static final Pattern DDL_AUTO = Pattern.compile("ddl-auto:\\s*['\"]?([\\w-]+)['\"]?");

    public static RuleResult check(String rootPath) {
        File root = new File(rootPath);
        RuleResult result = new RuleResult("no-orm-autosync-in-prod-config");

        List<File> ymlFiles = new ArrayList<>();
        collectYmlFiles(root, ymlFiles);
        ymlFiles.sort(Comparator.comparing(File::getPath));

        File defaultFile = findFirst(ymlFiles, DEFAULT_NAMES);
        File prodFile = findFirst(ymlFiles, PROD_NAMES);

        if (defaultFile == null && prodFile == null) {
            result.add(Finding.skip("application.yml / application-prod.yml 없음"));
            return result;
        }

        if (defaultFile != null) checkFile(result, root, defaultFile, "기본(프로필 없음)");
        if (prodFile != null) checkFile(result, root, prodFile, "prod 프로필");

        return result;
    }

    private static void checkFile(RuleResult result, File root, File file, String profileLabel) {
        String rel = relTo(file, root);
        String content = readText(file);
        Matcher m = DDL_AUTO.matcher(content);

        if (!m.find()) {
            result.add(Finding.pass(rel + " (" + profileLabel + " — ddl-auto 미설정, 자동 스키마 동기화 없음)"));
            return;
        }

        String value = m.group(1);
        if (DISALLOWED_VALUES.contains(value)) {
            result.add(Finding.fail(rel,
                profileLabel + " spring.jpa.hibernate.ddl-auto: " + value
                    + " — ORM 자동 스키마 동기화 금지, validate/none + Flyway/Liquibase 마이그레이션만 허용(persistence.md)"));
        } else {
            result.add(Finding.pass(rel + " (" + profileLabel + " — ddl-auto: " + value + ", 허용된 값)"));
        }
    }

    private static File findFirst(List<File> files, Set<String> names) {
        for (File f : files) {
            if (names.contains(f.getName())) return f;
        }
        return null;
    }

    private static void collectYmlFiles(File dir, List<File> out) {
        if (!dir.exists()) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) {
                if (!EXCLUDED_DIRS.contains(child.getName())) {
                    collectYmlFiles(child, out);
                }
            } else {
                String name = child.getName();
                if (name.endsWith(".yml") || name.endsWith(".yaml")) out.add(child);
            }
        }
    }
}

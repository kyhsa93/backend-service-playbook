package harness.rules;

import harness.Finding;
import harness.RuleResult;

import java.io.File;
import java.util.regex.Pattern;

import static harness.JavaFiles.readText;

/**
 * [22] Dockerfile 관례 — container.md가 요구하는 최소 조건을 프로젝트 루트의 {@code Dockerfile}/
 * {@code .dockerignore}(plain text)에서 확인한다. 다른 규칙과 달리 .java 파일이 아니라 두 텍스트
 * 파일 자체를 파싱한다.
 *
 * <ul>
 *   <li>멀티스테이지 빌드 — {@code FROM} 줄이 2개 이상(container.md: "멀티스테이지 빌드로 최종
 *   이미지에 빌드 도구/소스가 남지 않게 한다")</li>
 *   <li>{@code HEALTHCHECK} 지시문 존재</li>
 *   <li>{@code .dockerignore} 존재 + 최소한의 제외 패턴(빌드 산출물/VCS/시크릿) 포함</li>
 * </ul>
 */
public final class DockerfileConventions {
    private DockerfileConventions() {
    }

    private static final Pattern FROM_LINE = Pattern.compile("(?m)^\\s*FROM\\s+", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEALTHCHECK_LINE = Pattern.compile("(?m)^\\s*HEALTHCHECK\\s+", Pattern.CASE_INSENSITIVE);

    public static RuleResult check(String rootPath) {
        RuleResult result = new RuleResult("dockerfile-conventions");
        File dockerfile = new File(rootPath, "Dockerfile");

        if (!dockerfile.isFile()) {
            result.add(Finding.skip("Dockerfile 없음"));
            return result;
        }

        String content = readText(dockerfile);

        long fromCount = FROM_LINE.matcher(content).results().count();
        if (fromCount >= 2) {
            result.add(Finding.pass("Dockerfile (멀티스테이지 빌드, FROM " + fromCount + "개 확인)"));
        } else {
            result.add(Finding.fail("Dockerfile",
                "멀티스테이지 빌드가 아님 — FROM 줄이 " + fromCount + "개, 최소 2개 이상이어야 함(빌드 도구/소스가 최종 이미지에 남지 않게, container.md)"));
        }

        if (HEALTHCHECK_LINE.matcher(content).find()) {
            result.add(Finding.pass("Dockerfile (HEALTHCHECK 확인)"));
        } else {
            result.add(Finding.fail("Dockerfile", "HEALTHCHECK 지시문 없음(container.md)"));
        }

        File dockerignore = new File(rootPath, ".dockerignore");
        if (!dockerignore.isFile()) {
            result.add(Finding.fail(".dockerignore", "파일 없음 — 빌드 컨텍스트에 불필요한 파일(build 산출물, .git, .env 등)이 포함될 위험(container.md)"));
        } else {
            String ignoreContent = readText(dockerignore);
            String lower = ignoreContent.toLowerCase();
            boolean excludesGit = lower.contains(".git");
            boolean excludesBuildOutput = lower.contains("build") || lower.contains("target") || lower.contains(".gradle");
            if (excludesGit && excludesBuildOutput) {
                result.add(Finding.pass(".dockerignore (.git/빌드 산출물 제외 확인)"));
            } else {
                result.add(Finding.fail(".dockerignore",
                    "필수 제외 패턴 부족 — .git 및 빌드 산출물(build/.gradle/target) 디렉토리를 제외해야 함(container.md)"));
            }
        }

        return result;
    }
}

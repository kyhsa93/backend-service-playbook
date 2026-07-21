package harness.rules;

import harness.Finding;
import harness.RuleResult;

import java.io.File;
import java.util.regex.Pattern;

import static harness.JavaFiles.collectJavaFiles;
import static harness.JavaFiles.nameWithoutExtension;
import static harness.JavaFiles.pathContains;
import static harness.JavaFiles.readText;
import static harness.JavaFiles.relTo;

/**
 * [28] Rate Limiting이 실제로 배선됐는지 — rate-limiting.md는 전역 {@code Filter}(메서드 기준)와
 * 엔드포인트별 {@code @RateLimiter}(Resilience4j 애노테이션) 이중 방어를 요구한다. "정의는 있지만
 * 실제로 적용되지 않는" dead code 회귀를 잡는다.
 *
 * <p>{@code RateLimitFilter}가 {@code RateLimiterConfig.custom().limitForPeriod(100)...}처럼
 * 제한 값을 필드에 하드코딩하면 {@code application.yml}/환경 변수로 배포 시점에 조정할 수 없다 —
 * 반드시 {@code RateLimiterRegistry}에서 named instance(`http-write`/`http-read`)를 동적으로
 * 조회해야 한다. 이 규칙은 세 가지를 확인한다:
 *
 * <ol>
 *   <li>{@code RateLimitFilter}가 {@code @Component}로 Spring bean 등록되어 있는지(Spring Boot가
 *   Filter bean을 자동으로 필터 체인에 등록해 실제 요청 경로에 적용됨 — 별도 등록 파일 없음)</li>
 *   <li>{@code RateLimiterConfig.custom(}으로 제한 값을 필드에 직접 하드코딩하지 않는지</li>
 *   <li>{@code RateLimiterRegistry}를 주입받아 {@code .rateLimiter(...)}로 named instance를
 *   동적으로 조회하는지</li>
 * </ol>
 *
 * <p>추가로 {@code interfaces/}(REST Controller) 어딘가에 {@code @RateLimiter} 애노테이션이 실제
 * 엔드포인트에 붙어 있는지도 확인한다 — 애노테이션 자체는 선택 사항(엔드포인트별 세분화)이라
 * 전혀 없어도 실패로 잡지 않고, "필터만 있고 애노테이션 방식은 아직 안 씀"을 SKIP으로 남긴다.
 */
public final class RateLimitWired {
    private RateLimitWired() {
    }

    private static final Pattern COMPONENT_ANNOTATION = Pattern.compile("@Component\\b");
    private static final Pattern HARDCODED_CONFIG = Pattern.compile("RateLimiterConfig\\s*\\.\\s*custom\\s*\\(");
    private static final Pattern REGISTRY_FIELD = Pattern.compile("RateLimiterRegistry");
    private static final Pattern REGISTRY_LOOKUP = Pattern.compile("\\.\\s*rateLimiter\\s*\\(");
    private static final Pattern DISABLED_REGISTRATION =
        Pattern.compile("RateLimitFilter[\\s\\S]{0,200}?setEnabled\\s*\\(\\s*false\\s*\\)");
    private static final Pattern RATE_LIMITER_ANNOTATION_USE =
        Pattern.compile("@(?:io\\.github\\.resilience4j\\.ratelimiter\\.annotation\\.)?RateLimiter\\s*\\(\\s*name\\s*=");

    public static RuleResult check(String rootPath) {
        File root = new File(rootPath);
        RuleResult result = new RuleResult("rate-limit-wired");

        File filterFile = null;
        for (File f : collectJavaFiles(root)) {
            if (nameWithoutExtension(f).equals("RateLimitFilter")) {
                filterFile = f;
                break;
            }
        }

        if (filterFile == null) {
            result.add(Finding.skip("common/web/RateLimitFilter.java(전역 Rate Limiting Filter) 없음"));
            return result;
        }

        String rel = relTo(filterFile, root);
        String code = stripComments(readText(filterFile));

        if (!COMPONENT_ANNOTATION.matcher(code).find()) {
            result.add(Finding.fail(rel,
                "RateLimitFilter에 @Component가 없음 — Spring bean으로 등록되지 않으면 필터 체인에 자동 적용되지 않는 dead code(rate-limiting.md)"));
        } else {
            result.add(Finding.pass(rel + " (@Component로 Spring bean 등록 확인)"));
        }

        if (HARDCODED_CONFIG.matcher(code).find()) {
            result.add(Finding.fail(rel,
                "RateLimiterConfig.custom()으로 제한 값을 필드에 직접 하드코딩함 — application.yml의 RateLimiterRegistry named instance를 조회해야 배포 시점 조정이 가능함(rate-limiting.md)"));
        } else if (REGISTRY_FIELD.matcher(code).find() && REGISTRY_LOOKUP.matcher(code).find()) {
            result.add(Finding.pass(rel + " (RateLimiterRegistry 동적 조회 확인 — 하드코딩 없음)"));
        } else {
            result.add(Finding.fail(rel,
                "RateLimiterRegistry에서 named instance를 조회하는 코드를 찾을 수 없음 — 제한 값이 배포 시점에 조정 가능한지 확인 불가(rate-limiting.md)"));
        }

        boolean disabledSomewhere = false;
        for (File f : collectJavaFiles(root)) {
            if (!pathContains(f, "/config/") && !pathContains(f, "/infrastructure/")) continue;
            String content = readText(f);
            if (DISABLED_REGISTRATION.matcher(content).find()) {
                disabledSomewhere = true;
                result.add(Finding.fail(relTo(f, root),
                    "RateLimitFilter를 FilterRegistrationBean.setEnabled(false)로 비활성화함 — 필터가 등록되어도 실제로 요청에 적용되지 않는 dead code(rate-limiting.md)"));
            }
        }
        if (!disabledSomewhere) {
            result.add(Finding.pass("RateLimitFilter를 명시적으로 비활성화하는 설정 없음(자동 필터 체인 적용 유지)"));
        }

        boolean annotationUsed = false;
        for (File f : collectJavaFiles(root)) {
            if (!pathContains(f, "/interfaces/")) continue;
            String content = stripComments(readText(f));
            if (RATE_LIMITER_ANNOTATION_USE.matcher(content).find()) {
                annotationUsed = true;
                result.add(Finding.pass(relTo(f, root) + " (@RateLimiter 애노테이션으로 엔드포인트별 세분화 적용 확인)"));
            }
        }
        if (!annotationUsed) {
            result.add(Finding.skip("interfaces/의 어떤 Controller도 @RateLimiter 애노테이션(엔드포인트별 세분화)을 쓰지 않음 — 전역 Filter만으로도 원칙상 허용되므로 실패는 아님"));
        }

        return result;
    }

    private static String stripComments(String content) {
        return content.replaceAll("/\\*[\\s\\S]*?\\*/", "").replaceAll("//.*", "");
    }
}

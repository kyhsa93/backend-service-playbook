package harness.rules;

import harness.Finding;
import harness.RuleResult;

import java.io.File;
import java.util.regex.Pattern;

import static harness.JavaFiles.collectJavaFiles;
import static harness.JavaFiles.nameWithoutExtension;
import static harness.JavaFiles.readText;
import static harness.JavaFiles.relTo;

/**
 * [24] Aggregate ID 형식 — 32자리 hex(하이픈 없음)만 허용한다. {@code UUID.randomUUID().toString()}은
 * 하이픈(4개)을 포함한 36자 문자열을 반환하므로, 그대로 Aggregate ID로 쓰면 안 되고 하이픈을
 * 제거해야 한다(aggregate-id.md — "Aggregate ID는 32자리 hex 문자열, UUID의 하이픈을 제거해 생성").
 *
 * <p>이 저장소는 ID 생성을 공용 유틸리티 {@code common/IdGenerator.java} 한 곳으로 모아뒀으므로,
 * 이 파일 하나만 검사하는 단일 파일 규칙이다 — 각 Aggregate가 각자 ID를 생성하는 구조라면 도메인마다
 * 반복 검사가 필요하겠지만, 현재 구조에서는 이 유틸리티가 유일한 생성 지점이다.
 */
public final class AggregateIdFormat {
    private AggregateIdFormat() {
    }

    private static final Pattern RANDOM_UUID = Pattern.compile("UUID\\s*\\.\\s*randomUUID\\s*\\(\\s*\\)");
    private static final Pattern STRIP_HYPHENS =
        Pattern.compile("\\.replace(?:All)?\\s*\\(\\s*\"-\"\\s*,\\s*\"\"\\s*\\)");

    public static RuleResult check(String rootPath) {
        File root = new File(rootPath);
        RuleResult result = new RuleResult("aggregate-id-format");

        File idGenerator = null;
        for (File f : collectJavaFiles(root)) {
            if (nameWithoutExtension(f).equals("IdGenerator")) {
                idGenerator = f;
                break;
            }
        }

        if (idGenerator == null) {
            result.add(Finding.skip("common/IdGenerator.java(ID 생성 유틸리티) 없음"));
            return result;
        }

        String rel = relTo(idGenerator, root);
        String code = stripComments(readText(idGenerator));

        if (!RANDOM_UUID.matcher(code).find()) {
            result.add(Finding.fail(rel,
                "UUID.randomUUID() 호출을 찾을 수 없음 — Aggregate ID 생성 메커니즘을 확인할 수 없음(aggregate-id.md)"));
            return result;
        }

        if (STRIP_HYPHENS.matcher(code).find()) {
            result.add(Finding.pass(rel + " (UUID의 하이픈 제거 확인 — 32자리 hex 생성)"));
        } else {
            result.add(Finding.fail(rel,
                "UUID.randomUUID().toString()의 하이픈을 제거하지 않음 — 36자 하이픈 포함 문자열이 아니라 32자리 hex를 반환해야 함(.replace(\"-\", \"\") 필요, aggregate-id.md)"));
        }

        return result;
    }

    private static String stripComments(String content) {
        return content.replaceAll("/\\*[\\s\\S]*?\\*/", "").replaceAll("//.*", "");
    }
}

package harness.rules;

import harness.Finding;
import harness.RuleResult;

import java.io.File;

import static harness.JavaFiles.collectJavaFiles;
import static harness.JavaFiles.pathContains;
import static harness.JavaFiles.readText;
import static harness.JavaFiles.relTo;

/**
 * [16] Aggregate 간 직접 참조 금지 — 같은 Bounded Context 안에 여러 Aggregate가 있을 때
 * (Payment BC의 {@code Payment}/{@code Refund}), 한 Aggregate가 다른 Aggregate의 타입을
 * 필드/생성자 파라미터로 직접 들고 있으면 안 된다. ID 문자열({@code paymentId} 등) 참조만
 * 허용된다(domain-service.md — "Refund는 원 결제의 금액·상태를 모른다(paymentId로 참조만 한다)").
 *
 * <p>{@code payment/domain/}으로 범위를 좁힌다 — 현재 이 저장소에서 한 BC 안에 Aggregate가 둘
 * 이상인 유일한 실제 사례이기 때문이다(Account/Card BC는 각자 단일 Aggregate). 다른 BC에 두 번째
 * Aggregate가 생기면 이 규칙을 그 경로까지 일반화해야 한다 — 지금은 실재하는 케이스만 정밀하게
 * 잡는 블록리스트 방식을 따른다(오탐 위험을 낮추기 위해 "domain/ 안의 모든 Aggregate 쌍"을
 * 추론하는 일반 규칙은 만들지 않았다 — Aggregate 여부를 정적으로 판별할 신뢰할 만한 신호가 없다).
 */
public final class NoCrossAggregateReference {
    private NoCrossAggregateReference() {
    }

    public static RuleResult check(String rootPath) {
        File root = new File(rootPath);
        RuleResult result = new RuleResult("no-cross-aggregate-reference");
        boolean found = false;

        for (File f : collectJavaFiles(root)) {
            if (!pathContains(f, "/payment/domain/")) continue;

            String fileName = f.getName();
            String otherAggregate;
            if (fileName.equals("Payment.java")) {
                otherAggregate = "Refund";
            } else if (fileName.equals("Refund.java")) {
                otherAggregate = "Payment";
            } else {
                continue;
            }

            found = true;
            String rel = relTo(f, root);
            String code = stripComments(readText(f));

            if (referencesType(code, otherAggregate)) {
                result.add(Finding.fail(rel,
                    "payment/domain/" + fileName + "가 다른 Aggregate 타입 '" + otherAggregate
                        + "'을 직접 참조 — ID 문자열 참조만 허용(예: paymentId), domain-service.md"));
            } else {
                result.add(Finding.pass(rel + " (다른 Aggregate 타입 미참조 확인)"));
            }
        }

        if (!found) result.add(Finding.skip("payment/domain/Payment.java 또는 Refund.java 없음"));
        return result;
    }

    // \b 단어 경계 정규식 대신 수동 스캔 — PaymentException/PaymentStatus 같이 대상 이름을 접두어로
    // 갖는 다른 식별자는 오탐하지 않아야 한다(양쪽 다 영숫자/밑줄이 아닌 경계일 때만 매치).
    private static boolean referencesType(String code, String typeName) {
        int idx = 0;
        while ((idx = code.indexOf(typeName, idx)) != -1) {
            boolean leftBoundary = idx == 0 || !isIdentifierChar(code.charAt(idx - 1));
            int end = idx + typeName.length();
            boolean rightBoundary = end >= code.length() || !isIdentifierChar(code.charAt(end));
            if (leftBoundary && rightBoundary) return true;
            idx = end;
        }
        return false;
    }

    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static String stripComments(String content) {
        return content.replaceAll("/\\*[\\s\\S]*?\\*/", "").replaceAll("//.*", "");
    }
}

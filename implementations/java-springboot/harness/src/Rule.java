package harness;

import java.util.function.Function;

/** 규칙 함수 시그니처 — projectRoot 절대/상대경로를 받아 RuleResult를 반환한다. */
public interface Rule extends Function<String, RuleResult> {
}

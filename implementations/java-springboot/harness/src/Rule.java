package harness;

import java.util.function.Function;

/** The rule function signature — takes an absolute or relative projectRoot path and returns a RuleResult. */
public interface Rule extends Function<String, RuleResult> {
}

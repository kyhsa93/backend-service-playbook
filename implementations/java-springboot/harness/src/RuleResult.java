package harness;

import java.util.ArrayList;
import java.util.List;

/** The result returned by a single rule — a section header plus the list of Findings to print under it. */
public final class RuleResult {
    public final String section;
    public final List<Finding> findings = new ArrayList<>();

    public RuleResult(String section) {
        this.section = section;
    }

    public void add(Finding finding) {
        findings.add(finding);
    }

    public long count(Kind kind) {
        return findings.stream().filter(f -> f.kind == kind).count();
    }
}

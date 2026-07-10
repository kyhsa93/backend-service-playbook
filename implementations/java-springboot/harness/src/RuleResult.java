package harness;

import java.util.ArrayList;
import java.util.List;

/** 규칙 하나가 반환하는 결과 — 섹션 헤더 + 그 아래 출력할 Finding 목록. */
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

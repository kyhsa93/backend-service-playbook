package main

// countKind는 테스트에서 RuleResult가 특정 Kind(Pass/Fail/Skip)의 항목을
// 몇 개 갖는지 셀 때 쓰는 작은 도우미다.
func countKind(result RuleResult, kind Kind) int {
	n := 0
	for _, f := range result.Findings {
		if f.Kind == kind {
			n++
		}
	}
	return n
}

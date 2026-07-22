package main

// countKind is a small test helper for counting how many entries in a
// RuleResult have a given Kind (Pass/Fail/Skip).
func countKind(result RuleResult, kind Kind) int {
	n := 0
	for _, f := range result.Findings {
		if f.Kind == kind {
			n++
		}
	}
	return n
}

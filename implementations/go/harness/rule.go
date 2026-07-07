package main

// Kind identifies whether a Finding is a pass, a failure, or a "not applicable" skip.
type Kind int

const (
	Pass Kind = iota
	Fail
	Skip
)

// Finding is a single reported outcome within a rule's section output.
// For Skip, Name carries the skip message and Reason is unused.
type Finding struct {
	Kind   Kind
	Name   string
	Reason string
}

// RuleResult is everything one rule check contributes: its section header
// and the ordered list of findings to print under it.
type RuleResult struct {
	Section  string
	Findings []Finding
}

func passFinding(name string) Finding {
	return Finding{Kind: Pass, Name: name}
}

func failFinding(name, reason string) Finding {
	return Finding{Kind: Fail, Name: name, Reason: reason}
}

func skipFinding(message string) Finding {
	return Finding{Kind: Skip, Name: message}
}

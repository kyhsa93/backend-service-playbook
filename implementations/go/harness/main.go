// Go Harness — checks Go project structure/naming rules
// Usage: go run . <projectRoot>
//
// Each rule is implemented in its own file (file_naming.go,
// repository_placement.go, ...) and each returns a RuleResult — this file
// only serves as the CLI entry point that defines the rule list and
// aggregates/prints the results. Per-rule regression tests are verified with
// <rule>_test.go + testdata/<rule>/ fixtures (see README.md).
package main

import (
	"fmt"
	"math"
	"os"
	"strings"
)

var rules = []func(string) RuleResult{
	checkFileNaming,
	checkDirectoryStructure,
	checkRepositoryPlacement,
	checkRepositoryNaming,
	checkHandlerPlacement,
	checkFilePlacement,
	checkSharedInfra,
	checkEventPlacement,
	checkOutboxDrainOrder,
	checkCQRSPattern,
	checkDomainLayerIsolation,
	checkInterfaceNoInfrastructure,
	checkNoCrossAggregateReference,
	checkNoDirectEnvAccess,
	checkCrossBCRepositoryInApplication,
	checkNoLoggingInDomain,
	checkSchedulerInInfrastructureOnly,
	checkNoSilentCatch,
	checkDockerfileConventions,
	checkAggregateIDFormat,
	checkErrorResponseSchema,
	checkSoftDeleteFilter,
	checkTypedErrorsOnly,
	checkRateLimitWired,
	checkNoGenericResponseKeys,
	checkQueryHandlerNoRawAggregate,
	checkNoCrossBCDomainImport,
	checkAPIDocumentation,
	checkUTCTimestampSource,
}

// ruleMaxScore is the fixed per-rule point budget. A rule contributes
// ruleMaxScore * (passCount / (passCount+failCount)) to the raw score when it
// produced at least one non-SKIP finding, and 0 (excluded from the
// normalization denominator) when every finding was SKIP — mirroring the
// nestjs harness's "maxScore = 0 means not applicable" convention.
const ruleMaxScore = 20

// bucket assigns a rule's section name to one of the nestjs harness's 6
// score-breakdown categories, reusing the same substring-matching approach as
// evaluators/shared/score.ts so results stay comparable across languages.
// Returns "" for rules with no clear category (also mirrors nestjs, where not
// every evaluator lands in a bucket).
func bucket(name string) string {
	c := strings.Contains
	switch {
	case c(name, "structure"):
		return "structure"
	case c(name, "layer"),
		c(name, "repository"),
		c(name, "cqrs"),
		c(name, "scheduler"),
		c(name, "domain-layer-isolation"),
		c(name, "interface-no-infrastructure"),
		c(name, "no-cross-aggregate-reference"),
		c(name, "no-cross-bc-repository-in-application"),
		c(name, "no-cross-bc-domain-import"),
		c(name, "soft-delete-filter"),
		c(name, "utc-timestamp-source"),
		c(name, "query-handler-no-raw-aggregate"),
		c(name, "outbox"),
		c(name, "shared-infra"),
		c(name, "no-direct-env-access"),
		c(name, "aggregate-id"),
		c(name, "logging"),
		c(name, "error-response-schema"),
		c(name, "typed-errors-only"),
		c(name, "no-silent-catch"),
		c(name, "handler-placement"),
		c(name, "file-placement"),
		c(name, "event-placement"):
		return "architecture"
	case c(name, "dockerfile"):
		return "runtime"
	case c(name, "no-generic-response-keys"),
		c(name, "api-documentation"),
		c(name, "rate-limit-wired"):
		return "api"
	default:
		return ""
	}
}

func gradeFor(total int) string {
	switch {
	case total >= 90:
		return "A"
	case total >= 80:
		return "B"
	case total >= 70:
		return "C"
	case total >= 60:
		return "D"
	default:
		return "F"
	}
}

func main() {
	root := "."
	if len(os.Args) > 1 {
		root = os.Args[1]
	}

	passCount, failCount := 0, 0
	rawScore, rawMax := 0.0, 0
	breakdown := map[string]float64{}
	breakdownMax := map[string]int{}
	skippedRules := 0

	for _, rule := range rules {
		result := rule(root)
		fmt.Printf("\n[%s]\n", result.Section)

		rulePass, ruleFail := 0, 0
		for _, f := range result.Findings {
			switch f.Kind {
			case Pass:
				passCount++
				rulePass++
				fmt.Printf("  PASS  %s\n", f.Name)
			case Fail:
				failCount++
				ruleFail++
				fmt.Printf("  FAIL  %s — %s\n", f.Name, f.Reason)
			case Skip:
				fmt.Printf("  SKIP  %s\n", f.Name)
			}
		}

		if rulePass+ruleFail == 0 {
			skippedRules++
			continue
		}

		ruleScore := ruleMaxScore * float64(rulePass) / float64(rulePass+ruleFail)
		rawScore += ruleScore
		rawMax += ruleMaxScore

		if b := bucket(result.Section); b != "" {
			breakdown[b] += ruleScore
			breakdownMax[b] += ruleMaxScore
		}
	}

	total := 0
	if rawMax > 0 {
		total = int(math.Round(rawScore / float64(rawMax) * 100))
	}

	fmt.Printf("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n")
	fmt.Printf(
		"%s (%d/100, raw %.1f/%d) — %d failure(s) across %d evaluator(s), %d skipped (not applicable)\n",
		gradeFor(total), total, rawScore, rawMax, failCount, len(rules), skippedRules,
	)
	for _, cat := range []string{"structure", "architecture", "runtime", "testing", "api", "semantics"} {
		if breakdownMax[cat] > 0 {
			fmt.Printf("  %-13s %.1f/%d\n", cat, breakdown[cat], breakdownMax[cat])
		}
	}

	if failCount == 0 {
		fmt.Printf("%d passed  PASS\n", passCount)
	} else {
		fmt.Printf("%d passed, %d failed  FAIL\n", passCount, failCount)
		os.Exit(1)
	}
}

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
	"os"
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
}

func main() {
	root := "."
	if len(os.Args) > 1 {
		root = os.Args[1]
	}

	passCount, failCount := 0, 0
	for _, rule := range rules {
		result := rule(root)
		fmt.Printf("\n[%s]\n", result.Section)
		for _, f := range result.Findings {
			switch f.Kind {
			case Pass:
				passCount++
				fmt.Printf("  PASS  %s\n", f.Name)
			case Fail:
				failCount++
				fmt.Printf("  FAIL  %s — %s\n", f.Name, f.Reason)
			case Skip:
				fmt.Printf("  SKIP  %s\n", f.Name)
			}
		}
	}

	fmt.Printf("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n")
	if failCount == 0 {
		fmt.Printf("%d passed  PASS\n", passCount)
	} else {
		fmt.Printf("%d passed, %d failed  FAIL\n", passCount, failCount)
		os.Exit(1)
	}
}

#!/usr/bin/env python3
"""The FastAPI Harness — CLI entry point

Each rule is split out into its own rules/<rule>.py module, and this file only defines
the rule list and aggregates/prints the results. See README.md for structure/regression
tests.
Usage: python harness.py <projectRoot>
"""

from __future__ import annotations

import sys

from rules import (
    aggregate_id_format,
    aggregate_no_public_setters,
    api_documentation,
    cqrs_pattern,
    directory_structure,
    dockerfile_conventions,
    domain_layer_isolation,
    domain_purity,
    error_response_schema,
    event_placement,
    file_naming,
    handler_placement,
    layer_dependency,
    no_cross_aggregate_reference,
    no_cross_bc_domain_import,
    no_cross_domain_repository_import,
    no_direct_env_access,
    no_generic_response_keys,
    no_notification_dependency_in_command,
    no_orm_autosync_in_prod_config,
    no_silent_except,
    outbox_no_sync_drain,
    query_handler_no_raw_aggregate,
    rate_limit_wired,
    repository_abc,
    repository_impl,
    repository_naming,
    scheduler_in_infrastructure_only,
    shared_infra,
    soft_delete_filter,
    typed_errors_only,
)
from rules.common import collect_py_files

# The fixed per-rule point budget. A rule contributes RULE_MAX_SCORE *
# (passCount / (passCount+failCount)) to the raw score when it produced at least one
# non-SKIP finding, and 0 (excluded from the normalization denominator) when every finding
# was SKIP — mirroring the nestjs harness's "maxScore = 0 means not applicable" convention.
RULE_MAX_SCORE = 20


# Maps a rule's section name to one of the nestjs harness's 6 score-breakdown categories,
# reusing the same substring-matching approach as evaluators/shared/score.ts so results stay
# comparable across languages. Returns None for rules with no clear category (also mirrors
# nestjs, where not every evaluator lands in a bucket — e.g. file-naming).
def bucket_for(name: str) -> str | None:
    if "structure" in name:
        return "structure"
    if any(
        keyword in name
        for keyword in (
            "layer",
            "repository",
            "cqrs",
            "scheduler",
            "domain-purity",
            "no-cross-aggregate-reference",
            "no-cross-bc-domain-import",
            "soft-delete-filter",
            "query-handler-no-raw-aggregate",
            "outbox",
            "shared-infra",
            "no-direct-env-access",
            "aggregate-id",
            "aggregate-no-public-setters",
            "error-response-schema",
            "typed-errors-only",
            "no-silent-except",
            "no-notification-dependency-in-command",
            "handler-placement",
            "event-placement",
        )
    ):
        return "architecture"
    if "dockerfile" in name or "no-orm-autosync-in-prod-config" in name:
        return "runtime"
    if any(keyword in name for keyword in ("no-generic-response-keys", "api-documentation", "rate-limit-wired")):
        return "api"
    return None


def grade_for(total: int) -> str:
    if total >= 90:
        return "A"
    if total >= 80:
        return "B"
    if total >= 70:
        return "C"
    if total >= 60:
        return "D"
    return "F"


RULES = [
    file_naming,
    repository_abc,
    repository_impl,
    repository_naming,
    handler_placement,
    domain_purity,
    directory_structure,
    shared_infra,
    event_placement,
    layer_dependency,
    no_notification_dependency_in_command,
    outbox_no_sync_drain,
    cqrs_pattern,
    domain_layer_isolation,
    aggregate_no_public_setters,
    no_cross_aggregate_reference,
    no_direct_env_access,
    no_cross_domain_repository_import,
    scheduler_in_infrastructure_only,
    no_silent_except,
    dockerfile_conventions,
    aggregate_id_format,
    error_response_schema,
    soft_delete_filter,
    typed_errors_only,
    rate_limit_wired,
    no_generic_response_keys,
    query_handler_no_raw_aggregate,
    no_cross_bc_domain_import,
    no_orm_autosync_in_prod_config,
    api_documentation,
]


def main() -> None:
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    py_files = collect_py_files(root)

    pass_count = 0
    fail_count = 0
    raw_score = 0.0
    raw_max = 0
    skipped_rules = 0
    breakdown: dict[str, float] = {}
    breakdown_max: dict[str, int] = {}

    for rule in RULES:
        result = rule.check(root, py_files)
        print(f"\n[{result.section}]")
        for finding in result.findings:
            if finding.kind == "pass":
                pass_count += 1
                print(f"  PASS  {finding.name}")
            elif finding.kind == "fail":
                fail_count += 1
                print(f"  FAIL  {finding.name} — {finding.reason}")
            else:
                print(f"  SKIP  {finding.name}")

        rule_pass = result.count("pass")
        rule_fail = result.count("fail")
        if rule_pass + rule_fail == 0:
            skipped_rules += 1
            continue

        rule_score = RULE_MAX_SCORE * rule_pass / (rule_pass + rule_fail)
        raw_score += rule_score
        raw_max += RULE_MAX_SCORE

        category = bucket_for(result.section)
        if category:
            breakdown[category] = breakdown.get(category, 0.0) + rule_score
            breakdown_max[category] = breakdown_max.get(category, 0) + RULE_MAX_SCORE

    total = round(raw_score / raw_max * 100) if raw_max > 0 else 0

    print("\n" + "━" * 41)
    print(
        f"{grade_for(total)} ({total}/100, raw {raw_score:.1f}/{raw_max}) — "
        f"{fail_count} failure(s) across {len(RULES)} evaluator(s), "
        f"{skipped_rules} skipped (not applicable)"
    )
    for category in ("structure", "architecture", "runtime", "testing", "api", "semantics"):
        if breakdown_max.get(category):
            print(f"  {category:<13} {breakdown[category]:.1f}/{breakdown_max[category]}")

    if fail_count == 0:
        print(f"{pass_count} passed  PASS")
    else:
        print(f"{pass_count} passed, {fail_count} failed  FAIL")
        sys.exit(1)


if __name__ == "__main__":
    main()

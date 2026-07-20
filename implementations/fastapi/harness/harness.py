#!/usr/bin/env python3
"""FastAPI Harness — CLI 진입점

각 규칙은 rules/<rule>.py 모듈로 분리되어 있고, 이 파일은 규칙 목록을 정의하고
결과를 집계·출력하는 역할만 한다. 구조/회귀 테스트는 README.md 참고.
Usage: python harness.py <projectRoot>
"""

from __future__ import annotations

import sys

from rules import (
    aggregate_id_format,
    aggregate_no_public_setters,
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
    no_cross_domain_repository_import,
    no_direct_env_access,
    no_notification_dependency_in_command,
    no_silent_except,
    outbox_no_sync_drain,
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
]


def main() -> None:
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    py_files = collect_py_files(root)

    pass_count = 0
    fail_count = 0
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

    print("\n" + "━" * 41)
    if fail_count == 0:
        print(f"{pass_count} passed  PASS")
    else:
        print(f"{pass_count} passed, {fail_count} failed  FAIL")
        sys.exit(1)


if __name__ == "__main__":
    main()

"""규칙별 회귀 테스트 — tests/fixtures/<rule>/good|bad-*/ 를 대상으로 실행한다.

harness/ 디렉토리를 sys.path에 넣어 `import rules`가 되게 한 뒤, 각 규칙 모듈의
`check(root, py_files)`를 fixture 루트에 대해 직접 호출한다.
"""

from __future__ import annotations

import os
import sys

import pytest

HARNESS_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if HARNESS_DIR not in sys.path:
    sys.path.insert(0, HARNESS_DIR)

from rules import (  # noqa: E402
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
from rules.common import collect_py_files  # noqa: E402

FIXTURES_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "fixtures")


def run(rule_module, fixture_path: str):
    root = os.path.join(FIXTURES_DIR, fixture_path)
    py_files = collect_py_files(root)
    return rule_module.check(root, py_files)


def assert_no_failures(result) -> None:
    assert result.count("fail") == 0, [f"{f.name} — {f.reason}" for f in result.findings if f.kind == "fail"]


def assert_has_failure(result) -> None:
    assert result.count("fail") > 0, "expected at least one failure, got none"


@pytest.mark.parametrize(
    "rule_module,fixture",
    [
        (file_naming, "file-naming/good"),
        (repository_abc, "repository-abc/good"),
        (repository_impl, "repository-impl/good"),
        (handler_placement, "handler-placement/good"),
        (domain_purity, "domain-purity/good"),
        (directory_structure, "directory-structure/good"),
        (shared_infra, "shared-infra/good"),
        (event_placement, "event-placement/good"),
        (layer_dependency, "layer-dependency/good"),
        (no_notification_dependency_in_command, "no-notification-dependency-in-command/good"),
        (outbox_no_sync_drain, "outbox-no-sync-drain/good"),
        (cqrs_pattern, "cqrs-pattern/good"),
        (repository_naming, "repository-naming/good"),
        (domain_layer_isolation, "domain-layer-isolation/good"),
        (aggregate_no_public_setters, "aggregate-no-public-setters/good"),
        (no_cross_aggregate_reference, "no-cross-aggregate-reference/good"),
        (no_direct_env_access, "no-direct-env-access/good"),
        (no_cross_domain_repository_import, "no-cross-domain-repository-import/good"),
        (scheduler_in_infrastructure_only, "scheduler-in-infrastructure-only/good"),
        (no_silent_except, "no-silent-except/good"),
        (dockerfile_conventions, "dockerfile-conventions/good"),
        (aggregate_id_format, "aggregate-id-format/good"),
        (error_response_schema, "error-response-schema/good"),
        (soft_delete_filter, "soft-delete-filter/good"),
        (typed_errors_only, "typed-errors-only/good"),
        (rate_limit_wired, "rate-limit-wired/good"),
        (no_generic_response_keys, "no-generic-response-keys/good"),
        (query_handler_no_raw_aggregate, "query-handler-no-raw-aggregate/good"),
        (no_cross_bc_domain_import, "no-cross-bc-domain-import/good"),
        (no_orm_autosync_in_prod_config, "no-orm-autosync-in-prod-config/good"),
    ],
)
def test_good_fixture_has_no_failures(rule_module, fixture):
    assert_no_failures(run(rule_module, fixture))


@pytest.mark.parametrize(
    "rule_module,fixture",
    [
        (file_naming, "file-naming/bad-camelcase"),
        (repository_abc, "repository-abc/bad-outside-domain"),
        (repository_impl, "repository-impl/bad-outside-infrastructure"),
        (handler_placement, "handler-placement/bad-wrong-dir"),
        (domain_purity, "domain-purity/bad-forbidden-import"),
        (directory_structure, "directory-structure/bad-missing-layer"),
        (event_placement, "event-placement/bad-wrong-dir"),
        (event_placement, "event-placement/bad-wrong-consumer-dir"),
        (layer_dependency, "layer-dependency/bad-imports-infra"),
        (no_notification_dependency_in_command, "no-notification-dependency-in-command/bad-has-dependency"),
        (outbox_no_sync_drain, "outbox-no-sync-drain/bad-references-outbox-relay"),
        (outbox_no_sync_drain, "outbox-no-sync-drain/bad-calls-drain-method"),
        (cqrs_pattern, "cqrs-pattern/bad-imports-repository"),
        (repository_naming, "repository-naming/bad-find-by"),
        (repository_naming, "repository-naming/bad-find-all"),
        (repository_naming, "repository-naming/bad-count"),
        (repository_naming, "repository-naming/bad-bare-save"),
        (repository_naming, "repository-naming/bad-bare-delete"),
        (repository_naming, "repository-naming/bad-update"),
        (domain_purity, "domain-purity/bad-logging-import"),
        (domain_layer_isolation, "domain-layer-isolation/bad-imports-infrastructure"),
        (domain_layer_isolation, "domain-layer-isolation/bad-imports-other-domain-application"),
        (aggregate_no_public_setters, "aggregate-no-public-setters/bad-setter"),
        (no_cross_aggregate_reference, "no-cross-aggregate-reference/bad-payment-references-refund"),
        (no_direct_env_access, "no-direct-env-access/bad-getenv-in-application"),
        (no_cross_domain_repository_import, "no-cross-domain-repository-import/bad-cross-domain-import"),
        (scheduler_in_infrastructure_only, "scheduler-in-infrastructure-only/bad-apscheduler-in-application"),
        (no_silent_except, "no-silent-except/bad-silent-except"),
        (dockerfile_conventions, "dockerfile-conventions/bad-no-healthcheck"),
        (dockerfile_conventions, "dockerfile-conventions/bad-single-stage"),
        (dockerfile_conventions, "dockerfile-conventions/bad-no-dockerignore"),
        (dockerfile_conventions, "dockerfile-conventions/bad-no-user"),
        (aggregate_id_format, "aggregate-id-format/bad-hyphenated-uuid"),
        (error_response_schema, "error-response-schema/bad-extra-field"),
        (error_response_schema, "error-response-schema/bad-missing-field"),
        (soft_delete_filter, "soft-delete-filter/bad-missing-column"),
        (soft_delete_filter, "soft-delete-filter/bad-missing-filter"),
        (typed_errors_only, "typed-errors-only/bad-generic-exception"),
        (rate_limit_wired, "rate-limit-wired/bad-not-registered"),
        (rate_limit_wired, "rate-limit-wired/bad-not-applied"),
        (no_generic_response_keys, "no-generic-response-keys/bad-generic-key"),
        (query_handler_no_raw_aggregate, "query-handler-no-raw-aggregate/bad-returns-raw-aggregate"),
        (no_cross_bc_domain_import, "no-cross-bc-domain-import/bad-imports-other-bc-domain"),
        (no_orm_autosync_in_prod_config, "no-orm-autosync-in-prod-config/bad-create-all-in-main"),
    ],
)
def test_bad_fixture_has_failure(rule_module, fixture):
    assert_has_failure(run(rule_module, fixture))


def test_shared_infra_bad_outbox_missing_files():
    assert_has_failure(run(shared_infra, "shared-infra/bad-outbox-missing-files"))


def test_shared_infra_bad_task_queue_misplaced():
    assert_has_failure(run(shared_infra, "shared-infra/bad-task-queue-misplaced"))

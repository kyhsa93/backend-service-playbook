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
    cqrs_pattern,
    directory_structure,
    domain_purity,
    event_placement,
    file_naming,
    handler_placement,
    layer_dependency,
    no_notification_dependency_in_command,
    outbox_drain_order,
    repository_abc,
    repository_impl,
    shared_infra,
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
        (outbox_drain_order, "outbox-drain-order/good"),
        (cqrs_pattern, "cqrs-pattern/good"),
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
        (outbox_drain_order, "outbox-drain-order/bad-missing-process-pending"),
        (outbox_drain_order, "outbox-drain-order/bad-wrong-order"),
        (cqrs_pattern, "cqrs-pattern/bad-imports-repository"),
    ],
)
def test_bad_fixture_has_failure(rule_module, fixture):
    assert_has_failure(run(rule_module, fixture))


def test_shared_infra_bad_outbox_missing_files():
    assert_has_failure(run(shared_infra, "shared-infra/bad-outbox-missing-files"))


def test_shared_infra_bad_task_queue_misplaced():
    assert_has_failure(run(shared_infra, "shared-infra/bad-task-queue-misplaced"))
